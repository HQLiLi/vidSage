package com.vidsage.provider.llm;


import com.vidsage.config.SubtitleCleanupProperties;
import com.vidsage.model.asr.TranscriptionSegment;
import com.vidsage.model.subtitle.SubtitleContext;
import com.vidsage.utils.subtitle.TextSimilarity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 字幕纠错：把 ASR 出来的错字、口头语、乱标点修掉，只做最小改动。
 *
 * <p>整份字幕按批丢给 LLM（批的粒度由 batchSize 句数 / maxBatchChars 字符双判据切，先到为准）。
 * 每批的回复都要过一遍 {@link #validate}：键集必须与原文完全一致、改写幅度不能超过相似度阈值
 * （相似度取「原文」与「术语替换基线」两者对候选的<b>较大值</b>，故术语替换本身不算改太狠，详见该方法的注释）。
 * 没过就带着原因重试，最多 maxSteps 轮；全败保留原文——宁可没纠，不可被带偏。
 *
 * <p>跨批上下文走两条路：<b>后窗</b>（下一批开头几行，管的是"后面"）和<b>对话历史</b>
 * （前面几批的原文与已采纳的纠正结果，成对留着，管的是"前面"）。历史是本类里唯一的可变状态，
 * 但它只在单次 {@link #clean} 调用内活着，调用结束即丢。
 */
@Slf4j
@Component
public class SubtitleCleanser {


    private final ChatClient cleanser;

    private final SubtitleCleanupProperties prop;


    private final static int LINES_AFTER = 2;

    /**
     * 结构化输出的「格式说明 + 解析器」。
     *
     * <p>这原本是 {@code .entity()} 在背后做的事：往提示词末尾拼 {@code getFormat()}、拿 {@code convert()}
     * 解析回复。改成多轮消息后我们要用模型回复的<b>原文</b>（重试历史里要放的正是它），
     * 而 {@code .entity()} 只给解析结果、不给原文，所以把这两步显式提出来自己做。
     */
    private static final BeanOutputConverter<Map<String, String>> FORMAT =
            new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
            });

    /**
     * 把批次键值对渲染成 JSON，供跨批历史用。
     *
     * <p>用 Jackson 而不是类里那套 {@code jsonQuote} 手工拼接（规范禁止手写 JSON）。
     * {@code ObjectMapper} 线程安全，且只渲染 {@code Map<String,String>} 这一种固定形状，
     * 做成静态的即可——不必为它改构造器签名。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public SubtitleCleanser(@Qualifier("subtitleCleanupChatClient") ChatClient cleanser,
                            SubtitleCleanupProperties prop
    ) {
        this.cleanser = cleanser;
        this.prop = prop;
    }

    /**
     * 清洗字幕
     *
     * <p>跨批上下文分两路：后窗（下一批开头几行）由本方法算好传下去；上一批的内容改走
     * {@code history}——每批被采纳的「批次原文 + 纠正结果」留成一对多轮往返，带给后续所有批次。
     * 历史比前窗更强：前窗给的是上一批<b>未清洗</b>的原文，历史给的是<b>已被采纳的纠正</b>。
     *
     * @param segments  拆分后的字幕列表
     * @param context   字幕上下文
     * @param reference 术语表
     * @return 清洗结果
     */
    public CleanseResult clean(List<TranscriptionSegment> segments,
                               SubtitleContext context,
                               String reference) {
        List<TranscriptionSegment> cleanedList = new ArrayList<>(segments.size());
        int corrected = 0;
        int retries = 0;
        int termHits = 0;
        int termApplied = 0;

        // 跨批对话历史。必须是本方法的局部变量——本类是单例，挂成字段会在并发请求之间串味，
        // 而且是静默的（虚拟线程已全局开启，两个请求真的会同时进来）。
        List<Message> history = new ArrayList<>();

        int maxSize = segments.size();
        int start = 0;
        int batchNo = 0;
        while (start < maxSize) {
            int end = start;
            int chars = 0;
            while (end < maxSize && end - start < prop.batchSize() && chars < prop.maxBatchChars()) {
                chars += safe(segments.get(end).text()).length();
                end++;
            }
            if (end == start) {
                end = start + 1;   // 单句就超字符上限时也得往前走，别死循环
            }
            batchNo++;

            // 本批次要清洗的字幕列表
            Map<String, String> original = new LinkedHashMap<>();
            for (int i = start; i < end; i++) {
                String indexStr = String.valueOf(i - start + 1);
                original.put(indexStr, safe(segments.get(i).text()));
            }

            // 后窗从整份列表取，跟批边界对齐。它给的是「下一批开头」，历史给不了——历史只覆盖过去
            List<String> after = new ArrayList<>();
            for (int i = end; i < Math.min(maxSize, end + LINES_AFTER); i++) {
                after.add(safe(segments.get(i).text()));
            }
            log.debug("字幕纠错批次: 序号={}, 句数={}, 历史轮数={}, 历史字符={}",
                    batchNo, end - start, history.size() / 2, historyChars(history));

            // 清洗
            AgentOutcome outcome = agentLoop(original, context, reference, after, history);

            Map<String, String> correctedMap = outcome.map();
            retries += outcome.retrySteps();

            // 术语替换指标：口径是保守近似，详见 termMetrics 的注释
            TermMetrics metrics = termMetrics(original, correctedMap, outcome.hits());
            termHits += metrics.hits();
            termApplied += metrics.applied();
            // DEBUG 而非 INFO：本类是 Provider 内部节点，级别分层见 logging-guidelines；
            // 汇总数另有 Service 层那条 INFO，这里要的是「哪一批、哪条术语没换」的细粒度
            log.debug("术语替换: 批次={}, 命中={}, 已替换={}, 未替换={}",
                    batchNo, metrics.hits(), metrics.applied(), metrics.unapplied());

            for (int i = start; i < end; i++) {
                TranscriptionSegment seg = segments.get(i);
                String key = String.valueOf(i - start + 1);
                // 模型回了 null（或掉了键）就回滚为未清洗字幕——不判 null 的话下面 equals 会 NPE
                String newText = correctedMap.get(key);
                if (newText == null) {
                    newText = safe(seg.text());
                }
                if (!newText.equals(safe(seg.text()))) {
                    corrected++;
                }
                // 时间戳是切分阶段定的，纠错不动它们
                cleanedList.add(new TranscriptionSegment(seg.beginTimeMs(), seg.endTimeMs(), newText));
            }

            start = end;
        }
        return new CleanseResult(cleanedList, corrected, retries, termHits, termApplied);
    }


    /**
     * 单批的 LLM 清洗：以跨批历史起手，批内校验不过就重试。
     *
     * <p>重试走<b>多轮消息</b>而不是重发同一条提示词：模型上次的回复作为 assistant 消息留在局部历史里，
     * 后面再追一条 user 说明哪条没过校验。这样模型看得见自己上一条错成什么样，
     * 而不是只收到一句「你错了，原因是 X」。
     *
     * <p><b>两条时间尺度上的取舍相反，改代码时注意</b>：批内重试<b>要</b>把失败回复留进局部
     * {@code messages}（帮当前这批改对）；跨批的 {@code history} <b>不</b>收失败往返，只收被采纳的那次——
     * 否则等于告诉后面的批次「这种输出被接受过」。所以这里先拷贝 history，绝不直接往里写重试往返。
     *
     * @param original  待清洗的列表
     * @param context   上下文
     * @param reference 术语表
     * @param after     后n行（下一批开头）；历史只覆盖过去，后窗给的是未来，替代不了
     * @param history   跨批对话历史；<b>本方法在成功后原地追加本批往返</b>（调用方持有，别当只读入参）
     * @return 清洗结果
     */
    private AgentOutcome agentLoop(Map<String, String> original, SubtitleContext context,
                                   String reference, List<String> after, List<Message> history) {
        int retries = 0;

        // 必须是副本：直接拿 history 会在批内重试时把失败往返写进长期记录，
        // 而且任务消息会在 history 里越堆越多，下一批收到上一批的任务两遍。
        List<Message> messages = new ArrayList<>(history);

        // 本批命中哪些术语：渲染术语参考、构造校验基线、统计指标三处共用同一份判定，
        // 这里一次算好往下传，避免三处各判一遍后将来分叉。
        List<SubtitleContext.Term> hits = hitTerms(original, context);

        // 格式说明拼在任务这条上——ChatClient 只会往最后一条 UserMessage 追加，
        // 而重试的反馈那条会成为最后一条，所以得自己把它钉在任务上。
        String task = buildUserPrompt(original, context, hits, reference, after);
        messages.add(new UserMessage(task + System.lineSeparator() + FORMAT.getFormat()));

        for (int step = 0; step < prop.maxSteps(); step++) {
            String raw = ask(messages);
            Map<String, String> parsed = parse(raw);
            String error = parsed == null ? "回复不是合法的 JSON 对象。" : validate(original, parsed, hits);
            if (error == null) {
                remember(history, original, raw);
                return new AgentOutcome(parsed, retries, hits);
            }
            retries++;
            appendRetry(messages, raw, error);
        }
        // 用尽重试仍未拿到合法回复：保留原文（宁可没纠，不可被带偏）。
        // 什么都没采纳，所以不给 history 留痕——否则后面所有批次都会看着一条坏样板。
        return new AgentOutcome(original, retries, hits);
    }


    /**
     * 把本批「原文 + 被采纳的回复」记进跨批历史。
     *
     * <p>存模型回复的<b>原文</b>而不是规范化 JSON：这样本类里 assistant 消息只有一种表示
     * （{@link #appendRetry} 里也是原文）。代价是历史里的 assistant 文本与「实际写进输出的文本」
     * 不是逐字节相等——差异只可能来自 {@link BeanOutputConverter} 剥掉的围栏/空白，
     * 以及 {@code validate} 阈值内允许的措辞差。别误以为二者恒等。
     *
     * @param history      跨批历史，原地追加
     * @param batch        本批的原文键值对
     * @param rawAccepted  本批被采纳那次的模型回复原文
     */
    private void remember(List<Message> history, Map<String, String> batch, String rawAccepted) {
        if (prop.historyMaxChars() <= 0) {
            history.clear();   // 0 = 关闭跨批历史，退回「每批只看自己」
            return;
        }
        history.add(new UserMessage(batchJson(batch)));
        history.add(new AssistantMessage(rawAccepted));

        int sizeBefore = history.size();
        trimHistory(history, prop.historyMaxChars());
        if (history.size() < sizeBefore) {
            log.debug("跨批历史超上限，丢弃最老的 {} 轮往返: 上限={} 字符",
                    (sizeBefore - history.size()) / 2, prop.historyMaxChars());
        }
    }


    /**
     * 按字符上限裁剪跨批历史，以「一对往返」为单位从最老的开始丢。
     *
     * <p>按对裁而不是按条裁：只留 assistant 会造出「孤立的 assistant」——模型看见一个没有对应问题的回答。
     * 成对增、成对删，历史才永远满足「user 开头、assistant 结尾、条数为偶数」。
     *
     * <p>守卫是 {@code size() > 2}：保证刚追加的那一对无论多长都保留。否则遇到「单批正文就超上限」
     * 的配置会把历史清空到 0，等于白算一次裁剪。
     *
     * <p>包级可见是为了直接单测（同 {@link #validate}）。
     *
     * @param history  跨批历史，原地裁剪
     * @param maxChars 字符上限
     */
    static void trimHistory(List<Message> history, int maxChars) {
        while (history.size() > 2 && historyChars(history) > maxChars) {
            history.subList(0, 2).clear();
        }
    }


    /**
     * 跨批历史的累计字符数。
     *
     * <p>只算历史，不含系统提示词、格式说明与本批任务——那些每次必须发、裁不掉，
     * 计进来会让上限的语义变成「整条请求的预算」，那是另一个旋钮。
     *
     * <p>是字符不是 token，属<b>粗代理</b>：中文约 1 字 ≈ 1 token（估算准），
     * 英文约 4 字 ≈ 1 token（偏保守，会提前裁剪）。不引入 tokenizer。
     *
     * @param history 跨批历史
     * @return 累计字符数
     */
    static int historyChars(List<Message> history) {
        int total = 0;
        for (Message m : history) {
            total += safe(m.getText()).length();
        }
        return total;
    }


    /**
     * 把一批键值对渲染成 JSON，供跨批历史里的 user 消息用。
     *
     * @param batch 批次键值对
     * @return JSON 文本；渲染失败时退回空对象
     */
    private static String batchJson(Map<String, String> batch) {
        try {
            return MAPPER.writeValueAsString(batch);
        } catch (JacksonException e) {
            // Map<String,String> 在正常情况下渲染不出问题；真出了也别把整批清洗打挂
            log.warn("批次 JSON 渲染失败，跨批历史里放空对象: reason={}", e.getMessage());
            return "{}";
        }
    }


    /**
     * 发一次请求，拿模型回复的原文。
     *
     * <p>用 {@code chatResponse()} 而不是 {@code entity()}：后者只给解析结果，拿不到原文，
     * 而重试历史里要放的正是原文。
     *
     * @param messages 本轮全部消息（含此前失败的往返）
     * @return 模型回复原文；请求本身失败（网络 / 限流）时为 {@code null}
     */
    private String ask(List<Message> messages) {
        try {
            ChatResponse response = cleanser.prompt().messages(messages).call().chatResponse();
            return response == null || response.getResult() == null
                    ? null
                    : response.getResult().getOutput().getText();
        } catch (RuntimeException e) {
            // 请求都没发出去：当作「没拿到回复」走重试，别把整批打挂
            return null;
        }
    }


    /**
     * 把模型回复解析成「条号 → 文本」。
     *
     * <p>{@link BeanOutputConverter#convert} 会先过一遍文本清洗（空白、思维链标签、代码围栏），
     * 与原先 {@code .entity()} 走的是同一条路。
     *
     * @param raw 模型回复原文
     * @return 键值对；不是合法 JSON 对象时为 {@code null}
     */
    private static Map<String, String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return FORMAT.convert(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }


    /**
     * 校验没过时，把「模型上次说了什么 + 为什么不行」追进对话历史。
     *
     * @param messages 对话历史，原地追加
     * @param raw      模型上次回复原文；为 {@code null} 表示请求没成功，不伪造 assistant 轮次
     * @param error    校验不过的原因
     */
    private static void appendRetry(List<Message> messages, String raw, String error) {
        if (raw != null && !raw.isBlank()) {
            messages.add(new AssistantMessage(raw));
        }
        messages.add(new UserMessage("上次回复校验未通过，原因：" + error
                + "\n请只做最小修正，重新输出包含全部条目的合法 JSON。"));
    }


    /**
     * 校验一次 LLM 回复是否「可采纳」。返回 {@code null} 表示通过，否则返回违规说明。
     *
     * <p>两个硬约束：键集必须与原文完全一致（不许掉行/加行）；每条改写幅度不能超过相似度阈值
     * （&lt;= 10 字用 similarityShortText，其余用 similarityNormal）。
     *
     * <p>相似度取「原文」「术语替换基线」两者对候选的<b>较大值</b>：术语替换是合法目标，
     * 只跟原文比会把正确的替换判成改太狠；只跟基线比又会在模型判断「读不通、故意不换」时
     * 把保留原文的正确结果判成改太狠。两个目标够到任一个就算过。
     *
     * @param original  原文键值对
     * @param candidate 模型回复键值对
     * @param hits      本批命中术语（用于构造替换基线）；无命中时传空列表即可
     * @return 违规说明；通过则 {@code null}
     */
    String validate(Map<String, String> original, Map<String, String> candidate,
                    List<SubtitleContext.Term> hits) {
        StringBuilder sb = new StringBuilder();
        for (String k : original.keySet()) {
            if (!candidate.containsKey(k)) {
                sb.append("缺少条目 ").append(k).append("；");
            }
        }
        for (String k : candidate.keySet()) {
            if (!original.containsKey(k)) {
                sb.append("多余条目 ").append(k).append("；");
            }
        }
        if (sb.length() > 0) {
            return sb.toString() + " 请确保返回与输入完全相同的条号集合。";
        }

        for (String k : original.keySet()) {
            String orig = original.get(k);
            String cand = candidate.get(k);
            // 原文与「术语全替换后的基线」都是合法目标，够到任一个就算没改太狠（详见方法注释）
            double best = Math.max(
                    TextSimilarity.ratio(orig, cand),
                    TextSimilarity.ratio(baselineOf(orig, hits), cand));
            double threshold = orig.length() <= 10 ? prop.similarityShortText() : prop.similarityNormal();
            if (best < threshold) {
                sb.append("条目 ").append(k).append(" 改动过大（相似度 ")
                        .append(String.format("%.2f", best * 100)).append("% < ")
                        .append(String.format("%.0f", threshold * 100)).append("%）：")
                        .append("原 \"").append(orig).append("\" 改成了 \"").append(cand).append("\"；");
            }
        }
        return sb.length() == 0 ? null : sb.toString() + " 请只修识别错误，保留原文措辞与结构，做最小改动。";
    }

    /**
     * 挑出本批正文里真出现过的术语。
     *
     * <p>判据是「本批原文拼成小写后包含 asr 的小写原文」。术语参考渲染、校验基线、替换指标
     * 三处都靠它，抽成一处共用，免得将来改判据时只改到一半。
     *
     * @param original 本批原文键值对
     * @param context  字幕上下文；可为 {@code null}
     * @return 命中的术语，顺序与 {@code context.terms()} 一致；无命中返回空列表
     */
    static List<SubtitleContext.Term> hitTerms(Map<String, String> original, SubtitleContext context) {
        if (context == null || context.terms().isEmpty()) {
            return List.of();
        }
        String batchText = String.join("\n", original.values()).toLowerCase(Locale.ROOT);
        List<SubtitleContext.Term> hits = new ArrayList<>();
        for (SubtitleContext.Term t : context.terms()) {
            if (t.asr().isBlank()) {
                continue;
            }
            if (batchText.contains(t.asr().toLowerCase(Locale.ROOT))) {
                hits.add(t);
            }
        }
        return hits;
    }


    /**
     * 造一条原文的「术语替换基线」：把其中命中术语的 asr 字面串换成 fixed。
     *
     * <p>基线代表「模型把本批术语全换掉」的目标文本。校验层把它当备选目标，免得跨书写系统的
     * 正确替换（相似度天然很低）被「最小改动」守卫判成改太狠。
     *
     * <p>asr 与 fixed 相同、或 fixed 为空的条目跳过——它们没法换，也不该参与判定。
     *
     * @param text 一条原文，可为 {@code null}
     * @param hits 本批命中术语
     * @return 替换后的基线文本；无命中时与原文本相同
     */
    static String baselineOf(String text, List<SubtitleContext.Term> hits) {
        String base = safe(text);
        for (SubtitleContext.Term t : hits) {
            if (t.asr().equals(t.fixed()) || t.fixed().isBlank()) {
                continue;
            }
            base = base.replace(t.asr(), t.fixed());
        }
        return base;
    }


    /**
     * 统计本批的术语替换情况，口径是保守近似：
     *
     * <p>对本批每个已渲染进术语参考、且 {@code asr != fixed} 的术语 t，取本批原文里含 asr 的
     * 条目集合 K；只要 K 中任一条的候选文本里出现了 fixed，就记 t 为「已替换」。
     *
     * <p>「保守」指：模型把 fixed 写成变体形（如 {@code Mittel Hau}、{@code Mittelhau（米特号）}）
     * 会被算成未替换。宁可多报一条让人去看，也不漏报——本次故障正是静默漏报造成的。
     *
     * @param original  本批原文键值对
     * @param candidate 本批候选（模型回复）；掉了的键按「没替换」算
     * @param hits      本批命中术语
     * @return 命中数、已替换数、未替换术语清单（{@code asr → fixed} 形态）
     */
    private static TermMetrics termMetrics(Map<String, String> original, Map<String, String> candidate,
                                           List<SubtitleContext.Term> hits) {
        int hitCount = 0;
        int applied = 0;
        List<String> unapplied = new ArrayList<>();
        for (SubtitleContext.Term t : hits) {
            if (t.asr().equals(t.fixed()) || t.fixed().isBlank()) {
                continue;   // 无需替换的条目从分子分母都排除
            }
            hitCount++;
            boolean replaced = false;
            for (Map.Entry<String, String> e : original.entrySet()) {
                if (!containsIgnoreCase(e.getValue(), t.asr())) {
                    continue;   // 这条不含 asr，不参与该术语的判定
                }
                String cand = candidate.get(e.getKey());
                if (cand != null && containsIgnoreCase(cand, t.fixed())) {
                    replaced = true;
                    break;
                }
            }
            if (replaced) {
                applied++;
            } else {
                unapplied.add(t.asr() + " → " + t.fixed());
            }
        }
        return new TermMetrics(hitCount, applied, unapplied);
    }


    /**
     * 不区分大小写的子串包含，与 {@link #hitTerms} 的命中判定同一套口径。
     */
    private static boolean containsIgnoreCase(String haystack, String needle) {
        return safe(haystack).toLowerCase(Locale.ROOT).contains(safe(needle).toLowerCase(Locale.ROOT));
    }


    private static String buildUserPrompt(Map<String, String> original, SubtitleContext context,
                                          List<SubtitleContext.Term> hits, String reference,
                                          List<String> after) {
        StringBuilder sb = new StringBuilder();

        // 1. 本批次后面的部分字幕，增加点前后批关联，缓解跨批次的语义坍塌问题。
        if (!after.isEmpty()) {
            sb.append("### Context Information\n")
                    .append("<subsequent_content>\n")
                    .append(String.join("\n", after))
                    .append("\n</subsequent_content>\n\n")
                    .append("以上后文仅供参考，不要修改，也不要出现在返回结果里。\n\n");
        }

        // 2. 全篇主题
        if (context != null && !context.theme().isBlank()) {
            sb.append("### 内容主题\n").append(context.theme()).append("\n\n");
        }

        // 3. 术语：只列本批真出现过的（命中判定已在 hitTerms 里做好，这里不重判）
        if (!hits.isEmpty()) {
            StringBuilder terms = new StringBuilder();
            for (SubtitleContext.Term t : hits) {
                terms.append("- \"").append(t.asr())
                        .append("\" → \"").append(t.fixed()).append("\"（候选正确写法）");
                if (t.note() != null && !t.note().isBlank()) {
                    terms.append("：").append(t.note());
                }
                terms.append('\n');
            }
            sb.append("### 术语参考\n").append(terms).append('\n');
        }

        // 4. 本批正文与修正要求
        sb.append("请修正以下字幕，术语按上面的术语参考判断是否替换；其余内容保持原语言，不要翻译：\n{");
        boolean first = true;
        for (Map.Entry<String, String> e : original.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("\n  \"").append(e.getKey()).append("\": ").append(jsonQuote(e.getValue()));
        }
        sb.append("\n}");

        // 5. 人工术语表（HTTP 查询参数传进来的，原样拼）
        if (reference != null && !reference.isBlank()) {
            sb.append("\n\n参考术语表：\n").append(reference);
        }
        return sb.toString();
    }


    /**
     * 把字符串包成可安全放进 JSON 的字面量（引号转义 + null 保护）。
     */
    private static String jsonQuote(String value) {
        if (value == null) {
            return "\"\"";
        }
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
        return "\"" + escaped + "\"";
    }


    private static String safe(String s) {
        return s == null ? "" : s;
    }


    /**
     * 清洗结果
     *
     * @param segments       清洗后的列表
     * @param correctedCount 本趟实际改变文本的段数
     * @param retrySteps     本趟触发的校验重试轮数
     * @param termHits       本趟命中（渲染进术语参考）的术语数，跨批次累计
     * @param termApplied    本趟已替换的术语数，跨批次累计；口径见 {@link #termMetrics}
     */
    public record CleanseResult(List<TranscriptionSegment> segments,
                                int correctedCount,
                                int retrySteps,
                                int termHits,
                                int termApplied) {
    }

    /**
     * agent loop 单次调用的产物：采纳的纠正 + 本轮重试次数 + 本批命中术语。
     */
    private record AgentOutcome(Map<String, String> map, int retrySteps,
                                List<SubtitleContext.Term> hits) {
    }

    /**
     * 单批术语替换指标：命中数、已替换数、未替换清单。
     */
    private record TermMetrics(int hits, int applied, List<String> unapplied) {
    }

}
