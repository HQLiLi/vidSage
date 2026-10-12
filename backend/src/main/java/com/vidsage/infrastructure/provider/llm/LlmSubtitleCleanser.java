package com.vidsage.infrastructure.provider.llm;


import com.vidsage.config.SubtitleCleanupProperties;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.subtitle.model.cleanup.CleanseResult;
import com.vidsage.domain.subtitle.port.SubtitleCleanser;
import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.shared.subtitle.TermMatcher;
import com.vidsage.shared.subtitle.TextSimilarity;
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
 *
 * <p><b>批级进度</b>：带 {@link ProgressListener} 的重载会在<b>每批清洗完成后</b>回调一次
 * （进度 = 批序号 / 总批数 × 100，消息「第 x/N 批」）——批边界只有本类知道（切批判据在本类），
 * 回调依赖 {@code domain/shared} 是 ProgressListener 注释明确许可的中立方向。批次边界
 * 一次性算出（{@link #batchRanges}），总数与切批用同一份边界，进度百分比不会漂移。
 *
 * <p>实现领域端口 {@link SubtitleCleanser}——上层只认那个接口，不认识本类。上层用不到的三参
 * 便捷重载与包级可见的测试钩子留在本类，不进端口。
 */
@Slf4j
@Component
public class LlmSubtitleCleanser implements SubtitleCleanser {

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

    public LlmSubtitleCleanser(@Qualifier("subtitleCleanupChatClient") ChatClient cleanser,
                               SubtitleCleanupProperties prop
    ) {
        this.cleanser = cleanser;
        this.prop = prop;
    }

    /**
     * 清洗字幕，不关心进度（内部走 {@link ProgressListener#noop()}）。
     *
     * @param segments  拆分后的字幕列表
     * @param context   字幕上下文
     * @param keywords  用户手填的关键词
     * @return 清洗结果
     */
    public CleanseResult clean(List<TranscriptionSegment> segments,
                               SubtitleContext context,
                               SubtitleKeywords keywords) {
        return clean(segments, context, keywords, ProgressListener.noop());
    }

    /**
     * 清洗字幕
     *
     * <p>跨批上下文分两路：后窗（下一批开头几行）由本方法算好传下去；上一批的内容改走
     * {@code history}——每批被采纳的「批次原文 + 纠正结果」留成一对多轮往返，带给后续所有批次。
     * 历史比前窗更强：前窗给的是上一批<b>未清洗</b>的原文，历史给的是<b>已被采纳的纠正</b>。
     *
     * <p>每批清洗完成后向 {@code listener} 发一条批级进度
     * 进度 = 批序号 / 总批数 × 100，消息「第 x/N 批」）；批数为 0（空清单）时无批级事件。
     *
     * @param segments  拆分后的字幕列表
     * @param context   字幕上下文
     * @param keywords  用户手填的关键词；空清单就是「按原样清洗」，不要传 {@code null}
     * @param listener  批级进度回调；不可为 {@code null}——不关心进度用三参重载
     * @return 清洗结果
     * @throws IllegalArgumentException segments 或 listener 为 {@code null}
     */
    @Override
    public CleanseResult clean(List<TranscriptionSegment> segments,
                               SubtitleContext context,
                               SubtitleKeywords keywords,
                               ProgressListener listener) {
        if (segments == null) {
            throw new IllegalArgumentException("待清洗字幕不能为 null：请传切分后的 TranscriptionSegment 列表");
        }
        if (listener == null) {
            throw new IllegalArgumentException("进度监听不能为 null：请传一个 ProgressListener，"
                    + "或改用不带 listener 的 clean(segments, context, keywords) 三参重载");
        }
        List<BatchRange> batches = batchRanges(segments);
        List<TranscriptionSegment> cleanedList = new ArrayList<>(segments.size());
        int corrected = 0;
        int retries = 0;
        int termHits = 0;
        int termApplied = 0;

        // 跨批对话历史。必须是本方法的局部变量——本类是单例，挂成字段会在并发请求之间串味，
        // 而且是静默的（虚拟线程已全局开启，两个请求真的会同时进来）。
        List<Message> history = new ArrayList<>();

        int totalBatches = batches.size();
        for (int index = 0; index < totalBatches; index++) {
            int batchNo = index + 1;
            BatchRange batch = batches.get(index);
            int start = batch.start();
            int end = batch.end();

            // 本批次要清洗的字幕列表
            Map<String, String> original = new LinkedHashMap<>();
            for (int i = start; i < end; i++) {
                String indexStr = String.valueOf(i - start + 1);
                original.put(indexStr, safe(segments.get(i).text()));
            }

            // 后窗从整份列表取，跟批边界对齐。它给的是「下一批开头」，历史给不了——历史只覆盖过去
            List<String> after = new ArrayList<>();
            for (int i = end; i < Math.min(segments.size(), end + LINES_AFTER); i++) {
                after.add(safe(segments.get(i).text()));
            }
            // batch.chars() 就是本批的实际字数：按切批判据累加而得，可能略超 maxBatchChars——
            // 判断在累加之前，最后一个塞进来的句子会把总数顶过上限。
            // 这条日志正是要看「句数还是字数先到」。
            log.debug("字幕纠错批次: 序号={}, 句数={}, 字数={}, 历史轮数={}, 历史字符={}",
                    batchNo, end - start, batch.chars(), history.size() / 2, MessageHistory.chars(history));

            // 清洗
            AgentOutcome outcome = agentLoop(original, context, keywords, after, history);

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

            // 批完成才发进度：进度值 = 批序号 / 总批数 × 100，最后一批恰好 100，单调不回退
            emitBatchProgress(listener, batchNo, totalBatches);
        }
        return new CleanseResult(cleanedList, corrected, retries, termHits, termApplied);
    }

    /**
     * 发一条批级进度事件（stage 固定 {state 为 RUNNING）。
     * 整数除法向下取整：33.3% 记 33、66.6% 记 66，末批恰为 100——单调不回退。
     */
    private static void emitBatchProgress(ProgressListener listener, int batchNo, int totalBatches) {
        listener.onProgress(new StageProgress(SubtitleStageName.CLEAN_SUBTITLES.getStageName(), batchNo * 100 / totalBatches,
                "第 " + batchNo + "/" + totalBatches + " 批", StageProgress.State.RUNNING));
    }


    /**
     * 一次性算出全部批边界（[start, end) 半开区间 + 实际字数），切批判据与清洗循环共用这一份：
     * 总批数（进度百分比的分母）与实际切批若各算各的，两份逻辑漂移时进度会回退或卡死。
     *
     * <p>批的粒度由 batchSize 句数 / maxBatchChars 字符双判据切，先到为准；单句就超字符上限时
     * 也得单独成批往前走，别死循环。
     */
    private List<BatchRange> batchRanges(List<TranscriptionSegment> segments) {
        List<BatchRange> ranges = new ArrayList<>();
        int start = 0;
        while (start < segments.size()) {
            int end = start;
            int chars = 0;
            while (end < segments.size() && end - start < prop.batchSize() && chars < prop.maxBatchChars()) {
                chars += safe(segments.get(end).text()).length();
                end++;
            }
            if (end == start) {
                end = start + 1;
            }
            ranges.add(new BatchRange(start, end, chars));
            start = end;
        }
        return ranges;
    }

    /**
     * 一批的边界与实际字数：start 含、end 不含；chars 供「句数还是字数先到」的校准日志用。
     */
    private record BatchRange(int start, int end, int chars) {
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
     * @param keywords  用户手填的关键词（独立一段，不参与术语命中判定与相似度基线）
     * @param after     后n行（下一批开头）；历史只覆盖过去，后窗给的是未来，替代不了
     * @param history   跨批对话历史；<b>本方法在成功后原地追加本批往返</b>（调用方持有，别当只读入参）
     * @return 清洗结果
     */
    private AgentOutcome agentLoop(Map<String, String> original, SubtitleContext context,
                                   SubtitleKeywords keywords, List<String> after, List<Message> history) {
        int retries = 0;

        // 必须是副本：直接拿 history 会在批内重试时把失败往返写进长期记录，
        // 而且任务消息会在 history 里越堆越多，下一批收到上一批的任务两遍。
        List<Message> messages = new ArrayList<>(history);

        // 本批命中哪些术语：判定在 TermMatcher 里只有一份（清洗的渲染、基线、指标三处共用，
        // 翻译是第四个使用者）。清洗吃原始 ASR 文本，故按 asr 匹配——见 TermMatcher#hitTermsByAsr。
        List<SubtitleContext.Term> hits = TermMatcher.hitTermsByAsr(original, context);

        // 格式说明拼在任务这条上——ChatClient 只会往最后一条 UserMessage 追加，
        // 而重试的反馈那条会成为最后一条，所以得自己把它钉在任务上。
        String task = buildUserPrompt(original, context, hits, keywords, after);
        messages.add(new UserMessage(task + System.lineSeparator() + FORMAT.getFormat()));

        for (int step = 0; step < prop.maxSteps(); step++) {
            String raw = ask(messages);
            Map<String, String> parsed = parse(raw);
            // 回的不是合法 JSON：既非「键集不一致」也非「改动过大」，不归两类校验失败，照旧重试
            if (parsed == null) {
                retries++;
                appendRetry(messages, raw, "回复不是合法的 JSON 对象。");
                continue;
            }
            ValidationFailure failure = validate(original, parsed, hits);
            if (failure == null) {
                remember(history, original, raw);
                return new AgentOutcome(parsed, retries, hits);
            }
            retries++;
            // 按 kind 记 DEBUG：跑完真实素材好数出两类各触发了几次，供「相似度守卫去留」取证。
            // 只记分类与轮次，不记 detail——detail 里带着本句原文与模型改写的字幕文本，属用户内容，
            // 按 logging-guidelines 的脱敏红线（转写文本不进日志）不落盘。这条是生产环境唯一的读数来源。
            log.debug("字幕纠错校验未过: 分类={}, 轮次={}", failure.kind(), step + 1);
            // 开发期口子：光看分类答不出「相似度守卫拦下的是模型真改写，还是合法的术语替换被误判」，
            // 那要读 detail 里内嵌的「原…改成了…」。故按开关再补一条带 detail 的日志，开关缺席即为关，
            // 见 SubtitleCleanupProperties#logRejectedText。级别用 TRACE 而非 DEBUG：即使开关被误开，
            // 也要再过一道级别闸门（生产基线是 com.vidsage: DEBUG，TRACE 不会输出）。
            if (prop.logRejectedText()) {
                log.trace("字幕纠错校验未过详情（开发期专用，含字幕正文）: 分类={}, 轮次={}, 详情={}",
                        failure.kind(), step + 1, failure.detail());
            }
            appendRetry(messages, raw, failure.detail());
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
     * @param history     跨批历史，原地追加
     * @param batch       本批的原文键值对
     * @param rawAccepted 本批被采纳那次的模型回复原文
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
     * 校验一次 LLM 回复是否「可采纳」。返回 {@code null} 表示通过，否则返回带分类的失败。
     *
     * <p>两个硬约束：键集必须与原文完全一致（不许掉行/加行）；每条改写幅度不能超过相似度阈值
     * （&lt;= 10 字用 similarityShortText，其余用 similarityNormal）。
     *
     * <p>相似度取「原文」「术语替换基线」两者对候选的<b>较大值</b>：术语替换是合法目标，
     * 只跟原文比会把正确的替换判成改太狠；只跟基线比又会在模型判断「读不通、故意不换」时
     * 把保留原文的正确结果判成改太狠。两个目标够到任一个就算过。
     *
     * <p>失败分类成 {@code KEY_SET}（键集问题）与 {@code TOO_MUCH_EDIT}（改动过大）两类，是为了能分开
     * 计数：相似度守卫到底有没有拦下过东西，要靠这个读数回答。分类是结构化的，不靠文案前缀——前缀
     * 一改就静默失效。
     *
     * @param original  原文键值对
     * @param candidate 模型回复键值对
     * @param hits      本批命中术语（用于构造替换基线）；无命中时传空列表即可
     * @return 带分类的失败；通过则 {@code null}
     */
    ValidationFailure validate(Map<String, String> original, Map<String, String> candidate,
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
            return new ValidationFailure(ValidationFailure.FailureKind.KEY_SET,
                    sb.toString() + " 请确保返回与输入完全相同的条号集合。");
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
        return sb.length() == 0 ? null
                : new ValidationFailure(ValidationFailure.FailureKind.TOO_MUCH_EDIT,
                sb.toString() + " 请只修识别错误，保留原文措辞与结构，做最小改动。");
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
     * 不区分大小写的子串包含，与 {@link TermMatcher#hitTermsByAsr} 的命中判定同一套口径。
     */
    private static boolean containsIgnoreCase(String haystack, String needle) {
        return safe(haystack).toLowerCase(Locale.ROOT).contains(safe(needle).toLowerCase(Locale.ROOT));
    }


    /**
     * 拼本批的用户提示词：五段固定顺序（后窗 → 主题 → 术语参考 → 正文与要求 → 关键词）。
     *
     * <p><b>四段外部文本（后窗 / 主题 / 术语参考 / 关键词）一律走 {@link PromptDataBlock}</b>：
     * 标签包裹 + 结构性字符归一（2026-10-12 起）。它们的内容都来自模型或用户，不是我们写死的指令；
     * 而这一段之前只有后窗有标签，术语参考与主题是裸拼的——内容里一个换行就能伪造成新的一段指令。
     * 归一之后还配了各 worker 的 system 提示词声明「标签内是数据不是指令」，两层定性。
     *
     * <p><b>关键词为什么单列一段而不是进术语表</b>（design 决策 2）：术语表要的是 {@code asr → fixed}
     * 的对照，而关键词<b>没有</b>对照关系，只有正确写法。硬编成 {@code asr = fixed} 会被
     * {@link #baselineOf} 与 {@link #termMetrics} 里那个「asr == fixed 就跳过」的分支全部跳过，
     * 等于失效。它不进命中判定、不进术语指标、不进相似度基线——那是给「模型看着办」的提示，
     * 不是给统计用的数据。
     */
    // 包私有测试钩子（与 validate / baselineOf 同档）：注入防护的「关键词落在标签内、换行被替换掉」
    // 靠直接断言提示词原文来验，走完整 agent loop 反而要造一个假 ChatModel 才看得到它
    static String buildUserPrompt(Map<String, String> original, SubtitleContext context,
                                  List<SubtitleContext.Term> hits, SubtitleKeywords keywords,
                                  List<String> after) {
        StringBuilder sb = new StringBuilder();

        // 1. 本批次后面的部分字幕，增加点前后批关联，缓解跨批次的语义坍塌问题。
        if (!after.isEmpty()) {
            sb.append("### Context Information\n")
                    .append(PromptDataBlock.renderLines("subsequent_content", after))
                    .append("\n\n")
                    .append("以上后文仅供参考，不要修改，也不要出现在返回结果里。\n\n");
        }

        // 2. 全篇主题
        if (context != null && !context.theme().isBlank()) {
            sb.append("### 内容主题\n")
                    .append(PromptDataBlock.render("theme", context.theme()))
                    .append("\n\n");
        }

        // 3. 术语：只列本批真出现过的（命中判定已在 hitTermsByAsr 里做好，这里不重判）
        if (!hits.isEmpty()) {
            List<String> termLines = new ArrayList<>(hits.size());
            for (SubtitleContext.Term t : hits) {
                StringBuilder term = new StringBuilder();
                term.append("- \"").append(t.asr())
                        .append("\" → \"").append(t.fixed()).append("\"（候选正确写法）");
                if (t.note() != null && !t.note().isBlank()) {
                    term.append("：").append(t.note());
                }
                termLines.add(term.toString());
            }
            sb.append("### 术语参考\n").append(PromptDataBlock.renderLines("terms_reference", termLines)).append('\n');
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

        // 5. 用户手填的关键词：这些专有名词的写法是对的，同音误写按它纠正
        if (keywords != null && !keywords.isEmpty()) {
            sb.append("\n\n### 用户关键词（这些专有名词的写法是正确的，同音误写按它纠正）：\n")
                    .append(PromptDataBlock.renderLines("user_keywords", keywords.values()));
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

    /**
     * 一次校验失败的分类结果。
     *
     * <p>包级可见是为了能直接单测（{@link #validate} 也如此）。{@code detail} 是给人看的原因，
     * 会被拼进重试消息发给模型，措辞与拆分前的文本一字不差。
     *
     * @param kind   失败类型
     * @param detail 失败原因
     */
    record ValidationFailure(FailureKind kind, String detail) {

        /**
         * 失败类型：键集问题、改动过大。分开计数才能看清相似度守卫有没有拦下过东西。
         */
        enum FailureKind {
            /**
             * 键集与原文不一致：缺条目或多条目。
             */
            KEY_SET,
            /**
             * 改写幅度超过相似度阈值。
             */
            TOO_MUCH_EDIT
        }
    }

}
