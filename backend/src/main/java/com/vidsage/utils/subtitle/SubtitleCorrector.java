package com.vidsage.utils.subtitle;

import com.vidsage.model.asr.TranscriptionSegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 字幕纠错器：用 LLM 只改识别错误，同时把约束摁在「最小改动」上。
 *
 * <p>核心矛盾：LLM 天生爱「顺手改写」，而字幕纠错唯一允许的动作是<b>修错字、清口头语、规范标点</b>。
 * 本类用<b>守卫 + agent loop</b>把 LLM 摁住：每次回复先校验「有没有掉行」「有没有改写过度」，不通过就把
 * 具体违规拼回下一条提示让它重试，最多 {@code maxSteps} 轮。全败就<b>保留原文</b>——宁可没纠，也不能被带偏。
 *
 * <p>判「改写过度」用字符级相似度（{@link TextSimilarity}，对齐 difflib），不是向量——要拦的是「表面改得
 * 太多」，不是「语义是否等价」，详见 task design §4。
 *
 * <p>本类<b>不碰 LLM 具体实现</b>：构造时注入 {@link Llm} 函数式接口，由调用方接 Spring AI
 * {@code ChatModel}。这样 agent-loop / 守卫 / prompt 全部可拿桩测，不依赖模型细节。
 */
public class SubtitleCorrector {

    /**
     * LLM 调用口：给最终 system + user 提示词，返回模型纯文本回复。由外部接 {@code ChatModel}。
     */
    @FunctionalInterface
    public interface Llm {
        /**
         * @param system system 提示词
         * @param user   user 提示词（含批次 JSON；重试时含上一次输出与校验错误）
         * @return 模型回复原文
         */
        String complete(String system, String user);
    }

    /**
     * 纠错结果：修正后的 segments（条数与输入一致，只改 text）+ 统计。
     *
     * @param segments       修正后的分段（时间戳不变，仅 text 变化）
     * @param correctedCount 文本实际发生变化的段数
     * @param retrySteps     触发过校验重试的总轮次数（用于日志，判断 LLM 改得有多不老实）
     */
    public record CorrectionResult(List<TranscriptionSegment> segments, int correctedCount, int retrySteps) {
    }

    /** agent loop 单次调用的产物：采纳的纠正 + 本轮重试次数。 */
    private record AgentOutcome(Map<String, String> map, int retrySteps) {
    }

    private static final String SYSTEM_PROMPT = """
            你是专业的字幕修正助手。你的唯一任务是修正字幕里的识别错误，禁止翻译、禁止改写。

            对每条字幕只做最小改动：
            - 修错字（尤其是同音误写）；
            - 去掉口头语和语气词残渣（如 呃 / 嗯 / 啊 / 哈哈 / 笑声标记）；
            - 规范标点与大小写；
            - 保持原语言：中文保持中文，英文保持英文，绝不翻译。
            保持条目标号不变：不许合并、不许拆分、不许删除或新增任何一条。
            若提供了术语表，用其中术语纠正误识别的专业名词。

            只输出一个 JSON 对象：键为输入条号（字符串），值为修正后的文本。
            不要输出任何解释、注释或 markdown 代码块。""";

    private final Llm llm;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int maxSteps;
    private final double similarityNormal;
    private final double similarityShortText;

    /**
     * @param llm                     LLM 调用口（外部接 ChatModel，不可为 null）
     * @param objectMapper            Jackson 3 序列化器
     * @param batchSize               每批字幕条数，必须 &gt; 0
     * @param maxSteps                agent loop 重试上限，必须 &gt;= 1
     * @param similarityNormal        常规文本的相似度下限（如 0.7）
     * @param similarityShortText     短文本（&lt;= 10 字）的相似度下限（如 0.3）
     * @throws IllegalArgumentException 任一参数非法
     */
    public SubtitleCorrector(Llm llm, ObjectMapper objectMapper, int batchSize, int maxSteps,
                             double similarityNormal, double similarityShortText) {
        if (llm == null) {
            throw new IllegalArgumentException("Llm 不能为 null：请接 Spring AI ChatModel");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("ObjectMapper 不能为 null");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须为正：" + batchSize);
        }
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps 必须 >= 1：" + maxSteps);
        }
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.maxSteps = maxSteps;
        this.similarityNormal = similarityNormal;
        this.similarityShortText = similarityShortText;
    }

    /**
     * 纠错整份 segments，逐条返回修正后文本（时间戳不变、条数不变）。
     *
     * @param segments  待纠错的分段（建议先经切分）
     * @param reference 可选术语表 / 参考；可为 {@code null} 或空白（不提供）
     * @return 修正结果 + 统计
     */
    public CorrectionResult correct(List<TranscriptionSegment> segments, String reference) {
        List<TranscriptionSegment> out = new ArrayList<>(segments.size());
        int corrected = 0;
        int retries = 0;

        for (int start = 0; start < segments.size(); start += batchSize) {
            int end = Math.min(start + batchSize, segments.size());
            List<TranscriptionSegment> batch = segments.subList(start, end);

            // 编成 "1".."n" 的键→文本对交给 LLM；键对齐位置，守卫才能校「有没有少哪条」
            Map<String, String> original = new LinkedHashMap<>();
            for (int i = 0; i < batch.size(); i++) {
                original.put(String.valueOf(i + 1), safe(batch.get(i).text()));
            }

            AgentOutcome outcome = agentLoop(original, reference);
            Map<String, String> correctedMap = outcome.map();
            retries += outcome.retrySteps();

            for (int i = 0; i < batch.size(); i++) {
                TranscriptionSegment seg = batch.get(i);
                String key = String.valueOf(i + 1);
                String newText = correctedMap.getOrDefault(key, safe(seg.text()));
                if (!newText.equals(safe(seg.text()))) {
                    corrected++;
                }
                // 时间戳是切分阶段定的，纠错不动它们
                out.add(new TranscriptionSegment(seg.beginTimeMs(), seg.endTimeMs(), newText));
            }
        }

        return new CorrectionResult(out, corrected, retries);
    }

    /**
     * 单批 LLM 纠错 + agent loop：LLM 出 → 校验 → 违规拼回重试，最多 {@code maxSteps} 轮，全败保留原文。
     *
     * @param original  原始文本键值对（键 "1".."n"）
     * @param reference 可选术语表
     * @return 采纳的结果（键集与 original 一致）+ 重试轮数
     */
    private AgentOutcome agentLoop(Map<String, String> original, String reference) {
        String lastError = null;
        int retries = 0;

        for (int step = 0; step < maxSteps; step++) {
            String user = buildUserPrompt(original, reference, lastError);
            String content = llm.complete(SYSTEM_PROMPT, user);

            Map<String, String> parsed = parseResponse(content);
            if (parsed == null) {
                // 不是合法 JSON：反馈 + 重试
                lastError = "回复不是合法的 JSON 对象。";
                retries++;
                continue;
            }
            String error = validate(original, parsed);
            if (error == null) {
                return new AgentOutcome(parsed, retries);   // 校验通过，采纳
            }
            // 违规：把原因拼回下一条提示，重试
            lastError = error;
            retries++;
        }
        // 用尽重试仍未拿到合法回复：保留原文（宁可没纠，不可被带偏）
        return new AgentOutcome(original, retries);
    }

    // ---------------------------------------------------------------------
    // 守卫（纯函数，可单测）
    // ---------------------------------------------------------------------

    /**
     * 校验一次 LLM 回复是否「可采纳」。返回 {@code null} 表示通过，否则返回违规说明。
     *
     * <p>两个硬约束：键集必须与原文完全一致（不许掉行/加行）；每条改写幅度不能超过相似度阈值
     * （&lt;= 10 字用 similarityShortText，其余用 similarityNormal）。
     *
     * @param original  原文键值对
     * @param candidate 模型回复键值对
     * @return 违规说明；通过则 {@code null}
     */
    String validate(Map<String, String> original, Map<String, String> candidate) {
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
            double sim = TextSimilarity.ratio(orig, cand);
            double threshold = orig.length() <= 10 ? similarityShortText : similarityNormal;
            if (sim < threshold) {
                sb.append("条目 ").append(k).append(" 改动过大（相似度 ")
                        .append(String.format("%.2f", sim * 100)).append("% < ")
                        .append(String.format("%.0f", threshold * 100)).append("%）：")
                        .append("原 \"").append(orig).append("\" 改成了 \"").append(cand).append("\"；");
            }
        }
        return sb.length() == 0 ? null : sb.toString() + " 请只修识别错误，保留原文措辞与结构，做最小改动。";
    }

    /**
     * 容错解析模型回复为字符串键值对。剥掉可能的 markdown 代码块围栏；解析失败返回 {@code null}。
     *
     * @param content 模型回复原文
     * @return 键值对；失败则 {@code null}
     */
    private Map<String, String> parseResponse(String content) {
        if (content == null) {
            return null;
        }
        String stripped = stripCodeFence(content).trim();
        try {
            Map<String, String> map = objectMapper.readValue(
                    stripped, new TypeReference<Map<String, String>>() {
                    });
            return map == null ? Map.of() : map;
        } catch (JacksonException e) {
            return null;
        }
    }

    /**
     * 去掉回复首尾可能出现的 ```json / ``` 围栏（LLM 常爱包一层，剥掉才敢交给 Jackson）。
     */
    private static String stripCodeFence(String content) {
        String s = content.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
        }
        return s;
    }

    /**
     * 组装 user 提示词：批次 JSON + 可选术语表 + 失败反馈（重试时拼上）。
     *
     * @param original  原始键值对
     * @param reference 可选术语表
     * @param lastError 上一次校验失败原因；首次为 {@code null}
     * @return user 提示词
     */
    private static String buildUserPrompt(Map<String, String> original, String reference, String lastError) {
        StringBuilder sb = new StringBuilder("请修正以下字幕，保持原语言，不要翻译：\n{");
        boolean first = true;
        for (Map.Entry<String, String> e : original.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("\n  \"").append(e.getKey()).append("\": ").append(jsonQuote(e.getValue()));
        }
        sb.append("\n}");

        if (reference != null && !reference.isBlank()) {
            sb.append("\n\n参考术语表：\n").append(reference);
        }
        if (lastError != null && !lastError.isBlank()) {
            sb.append("\n\n上次回复校验未通过，原因：").append(lastError)
                    .append("\n请只做最小修正，重新输出包含全部条目的合法 JSON。");
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
}