package com.vidsage.provider.llm;

import com.vidsage.config.SubtitleTranslateProperties;
import com.vidsage.model.asr.TranscriptionSegment;
import com.vidsage.model.subtitle.SubtitleContext;
import com.vidsage.model.subtitle.TranslatedSegment;
import com.vidsage.utils.subtitle.TermMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 字幕翻译：把清洗后的每条字幕译成目标语种，术语与称谓全片一致。
 *
 * <p>整份字幕按批丢给 LLM（批的粒度由 batchSize 句数 / maxBatchChars 字符双判据切，先到为准）。
 * 每批的回复都要过一遍 {@link #validate}：键集必须与原文完全一致（防掉行 / 串行 / 多行）、每条译文
 * 非空。<b>不像清洗那样设相似度守卫</b>——译文与原文天然不相似，那套「最小改动」判据在翻译里没有
 * 意义。没过就带着原因重试，最多 maxSteps 轮；全败整批回退原文（宁可没译，不可乱译），并把回退的
 * 段数计进 {@link TranslateResult#untranslatedCount()}。
 *
 * <p>与 {@link SubtitleCleanser} <b>刻意各自独立</b>，本期不抽公共骨架（design 决策 8）：要抽的正是
 * 「切批判据」与「校验循环」，而这两套东西现在都还处于待审状态，抽了会返工。唯一共用的是一条已定型的
 * 纯函数判定——跨批历史的按对裁剪（{@link MessageHistory}）。
 *
 * <p>跨批上下文走两条路：<b>后窗</b>（下一批开头几行）和<b>对话历史</b>（前面几批的原文与已采纳的
 * 译文，成对留着）。历史在翻译里的作用比清洗更强：它是「术语与称谓全片一致」的主要载体——后续批次
 * 能看见前面把某个称呼译成了什么。历史是本类里唯一的可变状态，但它只在单次 {@link #translate} 调用内
 * 活着，调用结束即丢。
 */
@Slf4j
@Component
public class SubtitleTranslator {


    private final ChatClient translator;

    private final SubtitleTranslateProperties prop;


    private static final int LINES_AFTER = 2;

    /**
     * 结构化输出的「格式说明 + 解析器」。
     *
     * <p>与 {@link SubtitleCleanser} 同一套路：不用 {@code .entity()}，因为它只给解析结果、不给原文，
     * 而重试历史里要放的正是模型回复的原文。所以把「拼格式说明」与「解析」两步显式提出来自己做。
     */
    private static final BeanOutputConverter<Map<String, String>> FORMAT =
            new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
            });

    /**
     * 把批次键值对渲染成 JSON，供跨批历史用。
     *
     * <p>用 Jackson，不手写拼接（规范禁止手写 JSON）。{@code ObjectMapper} 线程安全，且只渲染
     * {@code Map<String,String>} 这一种固定形状，做成静态的即可。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public SubtitleTranslator(@Qualifier("subtitleTranslateChatClient") ChatClient translator,
                              SubtitleTranslateProperties prop) {
        this.translator = translator;
        this.prop = prop;
    }


    /**
     * 翻译字幕。
     *
     * @param segments       待翻译的段落（清洗后的 {@code -clean} 内容），时间戳原样带到产物
     * @param context        字幕上下文（主题 / 源语种 / 术语表）
     * @param targetLanguage 目标语种（BCP-47 短码）；源语种与目标语种相同的**跳过判定不在这里**，
     *                       由调用方在更外层做（那样才不会白白读一遍字幕才决定跳过）
     * @return 逐条双语段落 + 重试轮数 + 未翻译段数
     */
    public TranslateResult translate(List<TranscriptionSegment> segments,
                                     SubtitleContext context,
                                     String targetLanguage) {
        List<TranslatedSegment> out = new ArrayList<>(segments.size());
        int retries = 0;
        int untranslated = 0;

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

            // 本批次待翻译的字幕列表：条号从 1 起，与提示词里的键、模型回复的键对齐
            Map<String, String> original = new LinkedHashMap<>();
            for (int i = start; i < end; i++) {
                original.put(String.valueOf(i - start + 1), safe(segments.get(i).text()));
            }

            // 后窗从整份列表取，跟批边界对齐。它给的是「下一批开头」，历史给不了——历史只覆盖过去
            List<String> after = new ArrayList<>();
            for (int i = end; i < Math.min(maxSize, end + LINES_AFTER); i++) {
                after.add(safe(segments.get(i).text()));
            }
            // chars 就是本批的实际字数：内层循环把 [start, end) 每句长度都加了进去，循环退出时 end 已定。
            // 它可能略超 maxBatchChars——判断在累加之前，最后一个塞进来的句子会把总数顶过上限。
            // 这条日志是校准批参数的读数（句数与字数哪个先到，见 SubtitleTranslateProperties 的注释）。
            log.debug("字幕翻译批次: 序号={}, 句数={}, 字数={}, 历史轮数={}, 历史字符={}",
                    batchNo, end - start, chars, history.size() / 2, MessageHistory.chars(history));

            TranslateOutcome outcome = agentLoop(original, context, targetLanguage, after, history);
            retries += outcome.retrySteps();

            if (!outcome.translated()) {
                log.debug("字幕翻译批次重试用尽，整批回退原文: 序号={}, 句数={}", batchNo, end - start);
            }

            for (int i = start; i < end; i++) {
                TranscriptionSegment seg = segments.get(i);
                String translated = outcome.map().get(String.valueOf(i - start + 1));
                if (translated == null || !outcome.translated()) {
                    // 用尽重试没拿到可用译文：回退原文，并计数。静默产出未翻译的行是本条最需要防的事，
                    // 所以让它可观测（调用方能看到 untranslatedCount）。
                    translated = safe(seg.text());
                    untranslated++;
                }
                // 时间戳是切分阶段定的，翻译不动它们
                out.add(new TranslatedSegment(seg.beginTimeMs(), seg.endTimeMs(),
                        seg.text(), translated));
            }

            start = end;
        }
        return new TranslateResult(out, retries, untranslated);
    }


    /**
     * 单批的 LLM 翻译：以跨批历史起手，批内校验不过就重试。
     *
     * <p>重试走<b>多轮消息</b>而不是重发同一条提示词：模型上次的回复作为 assistant 消息留在局部历史里，
     * 后面再追一条 user 说明哪条没过校验。这样模型看得见自己上一条错成什么样。
     *
     * <p><b>两条时间尺度上的取舍相反，改代码时注意</b>：批内重试<b>要</b>把失败回复留进局部
     * {@code messages}（帮当前这批改对）；跨批的 {@code history} <b>不</b>收失败往返，只收被采纳的那次
     * ——否则等于告诉后面的批次「这种输出被接受过」。所以这里先拷贝 history，绝不直接往里写重试往返。
     *
     * @param original       本批原文键值对
     * @param context        上下文
     * @param targetLanguage 目标语种短码
     * @param after          后 n 行（下一批开头原文）；历史只覆盖过去，后窗给的是未来，替代不了
     * @param history        跨批对话历史；<b>本方法在成功后原地追加本批往返</b>（调用方持有，别当只读入参）
     * @return 本批的采纳结果 + 重试轮数 + 是否真拿到了译文（{@code false} 表示整批回退原文）
     */
    private TranslateOutcome agentLoop(Map<String, String> original, SubtitleContext context,
                                       String targetLanguage, List<String> after, List<Message> history) {
        int retries = 0;

        // 必须是副本：直接拿 history 会在批内重试时把失败往返写进长期记录，
        // 而且任务消息会在 history 里越堆越多，下一批收到上一批的任务两遍。
        List<Message> messages = new ArrayList<>(history);

        // 本批命中哪些术语：判定在 TermMatcher 里只有一份（清洗的渲染 / 基线 / 指标三处 + 翻译）。
        // 翻译吃清洗后的字幕（命中术语的字面多已被换成 fixed），故按 fixed 匹配——见 TermMatcher#hitTermsByFixed。
        List<SubtitleContext.Term> hits = TermMatcher.hitTermsByFixed(original, context);

        // 格式说明拼在任务这条上——ChatClient 只会往最后一条 UserMessage 追加，
        // 而重试的反馈那条会成为最后一条，所以得自己把它钉在任务上。
        String task = buildUserPrompt(original, context, hits, targetLanguage, after);
        messages.add(new UserMessage(task + System.lineSeparator() + FORMAT.getFormat()));

        for (int step = 0; step < prop.maxSteps(); step++) {
            String raw = ask(messages);
            Map<String, String> parsed = parse(raw);
            if (parsed == null) {
                retries++;
                appendRetry(messages, raw, "回复不是合法的 JSON 对象。");
                continue;
            }
            String failure = validate(original, parsed);
            if (failure == null) {
                remember(history, original, raw);
                return new TranslateOutcome(parsed, retries, true);
            }
            retries++;
            appendRetry(messages, raw, failure);
        }
        // 用尽重试仍未拿到可用译文：整批回退原文（宁可没译，不可乱译）。什么都没采纳，
        // 所以不给 history 留痕——否则后面所有批次都会看着一条坏样板。
        return new TranslateOutcome(original, retries, false);
    }


    /**
     * 把本批「原文 + 被采纳的译文回复」记进跨批历史。
     *
     * <p>存模型回复的<b>原文</b>而不是规范化 JSON：这样本类里 assistant 消息只有一种表示
     * （{@link #appendRetry} 里也是原文）。跨批历史是「术语与称谓全片一致」的主要载体——
     * 后续批次能看见前面把某个称呼译成了什么。
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
        MessageHistory.trimHistory(history, prop.historyMaxChars());
        if (history.size() < sizeBefore) {
            log.debug("跨批历史超上限，丢弃最老的 {} 轮往返: 上限={} 字符",
                    (sizeBefore - history.size()) / 2, prop.historyMaxChars());
        }
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
            // Map<String,String> 在正常情况下渲染不出问题；真出了也别把整批翻译打挂
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
            ChatResponse response = translator.prompt().messages(messages).call().chatResponse();
            return response == null || response.getResult() == null
                    ? null
                    : response.getResult().getOutput().getText();
        } catch (RuntimeException e) {
            // 请求都没发出去：当作「没拿到回复」走重试，别把整批打挂
            return null;
        }
    }


    /**
     * 把模型回复解析成「条号 → 译文」。
     *
     * <p>{@link BeanOutputConverter#convert} 会先过一遍文本清洗（空白、思维链标签、代码围栏），
     * 与 {@code .entity()} 走的是同一条路。
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
                + "\n请重新输出包含全部条目的合法 JSON，每条都给出非空译文。"));
    }


    /**
     * 校验一次 LLM 回复是否「可采纳」。返回 {@code null} 表示通过，否则返回原因（会被拼进重试消息）。
     *
     * <p>两条硬约束：键集必须与输入完全一致（不许掉行 / 加行 / 串行）；每条译文 trim 后非空。
     * <b>不设相似度守卫</b>：译文与原文天然不相似，「最小改动」那套判据在翻译里没有意义。
     *
     * <p>包级可见是为了直接单测（同 {@link SubtitleCleanser#validate}）。
     *
     * @param original  原文键值对
     * @param candidate 模型回复键值对
     * @return 失败原因；通过则 {@code null}
     */
    static String validate(Map<String, String> original, Map<String, String> candidate) {
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
            return sb + " 请确保返回与输入完全相同的条号集合。";
        }

        for (Map.Entry<String, String> e : candidate.entrySet()) {
            // hasText 即「trim 后非空」，与要求一字不差
            if (!StringUtils.hasText(e.getValue())) {
                sb.append("条目 ").append(e.getKey()).append(" 的译文为空；");
            }
        }
        return sb.length() == 0 ? null : sb + " 每条字幕都必须给出非空译文。";
    }


    private static String buildUserPrompt(Map<String, String> original, SubtitleContext context,
                                          List<SubtitleContext.Term> hits, String targetLanguage,
                                          List<String> after) {
        StringBuilder sb = new StringBuilder();

        // 1. 本批次后面的部分字幕，增加点前后批关联，缓解跨批次的语义坍塌问题。
        if (!after.isEmpty()) {
            sb.append("### 后文参考\n")
                    .append("<subsequent_content>\n")
                    .append(String.join("\n", after))
                    .append("\n</subsequent_content>\n\n")
                    .append("以上后文仅供参考，不要翻译，也不要出现在返回结果里。\n\n");
        }

        // 2. 全篇主题
        if (context != null && !context.theme().isBlank()) {
            sb.append("### 内容主题\n").append(context.theme()).append("\n\n");
        }

        // 3. 术语参考：语义与清洗侧相反——那边给的是「候选正确写法 + 替换理由」，让模型判断换不换；
        //    这边给的是「这个词是专有名词，照给定的写法输出、不要译」（命中判定已在 hitTermsByFixed 里做好）。
        if (!hits.isEmpty()) {
            sb.append("### 术语参考（以下写法是专有名词，翻译时保留原样，不要译成目标语种）\n");
            for (SubtitleContext.Term t : hits) {
                sb.append("- \"").append(t.fixed()).append("\"\n");
            }
            sb.append('\n');
        }

        // 4. 本批正文与翻译要求。目标语种在 system 提示词里已定型一次，这里再贴一遍：
        //    任务消息离正文最近，多轮重试时不易被历史冲淡。
        sb.append("请把以下字幕翻译成目标语种（BCP-47 短码：").append(targetLanguage).append("）：\n{");
        boolean first = true;
        for (Map.Entry<String, String> e : original.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("\n  \"").append(e.getKey()).append("\": ").append(jsonQuote(e.getValue()));
        }
        sb.append("\n}");
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
     * 翻译结果。
     *
     * @param segments          逐条双语段落，时间轴与输入逐条一致
     * @param retrySteps        本趟触发的校验重试轮数
     * @param untranslatedCount 本趟回退原文（用尽重试仍没拿到可用译文）的段数；跨批次累计
     */
    public record TranslateResult(List<TranslatedSegment> segments,
                                  int retrySteps,
                                  int untranslatedCount) {
    }

    /**
     * agent loop 单次调用的产物。
     *
     * @param map        采纳的译文键值对；{@code translated=false} 时就是本批原文
     * @param retrySteps 本轮重试次数
     * @param translated 是否真拿到了译文；{@code false} 表示整批回退原文
     */
    private record TranslateOutcome(Map<String, String> map, int retrySteps, boolean translated) {
    }

}
