package com.vidsage.utils.subtitle;

import com.vidsage.model.asr.TranscriptionSegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.vidsage.model.subtitle.SubtitleContext;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 跑一次全篇摘要，产出纠错用的跨批上下文（主题 + 术语）。
 *
 * <p><b>为什么要有它</b>：纠错是按批（默认 20 句）丢给 LLM 的，批与批之间原本完全断开——
 * 同一个人名在第 1 批和第 5 批写法不同时没人管。摘要先让模型看一眼全篇，把「这片子在讲什么、
 * 有哪些专有名词」提炼出来，之后每批都带上，跨批一致性才有兜底。
 *
 * <p><b>失败一律降级，绝不外抛</b>：模型报错、回非 JSON、结构不合法，统统返回
 * {@link SubtitleContext#empty()} 并记 WARN。上下文是增益不是前置条件，为一次可选的增强调用
 * 把整条清洗打挂是把可选依赖做成了硬依赖。
 *
 * <p>本类不碰 SDK 类型：构造时注入 {@link Llm} 函数式接口，system 提示词已固定在对方那个
 * ChatClient 上，这里只管给 user。
 */
@Slf4j
public final class SubtitleContextBuilder {

    /**
     * LLM 调用口：给 user 提示词，返回模型纯文本回复。
     *
     * <p>没有 system 参数——system 提示词固定在本接口背后的 ChatClient 上（见 config 的装配）。
     */
    @FunctionalInterface
    public interface Llm {
        /**
         * @param user user 提示词
         * @return 模型回复原文
         */
        String complete(String user);
    }

    /** 摘要用的 system 提示词。装配层把它挂到专属 ChatClient 上，不在调用点传。 */
    public static final String SUMMARY_SYSTEM_PROMPT = """
            你是字幕分析助手。给你一段视频字幕的开头部分，请提炼两个东西：

            1. 主题：用一句话说明这段内容在讲什么（话题、领域、场景）。
            2. 术语：里面出现的专有名词——人名、产品名、组织名、专业术语。
               同音误写的按正确写法给出，拿不准的宁可不给。

            只输出一个 JSON 对象，形如：
            {"theme": "……", "terms": [{"src": "原文写法", "tgt": "正确写法", "note": "简短说明"}]}

            没有术语就用空数组。不要输出任何解释、注释或 markdown 代码块。""";

    private final Llm llm;
    private final ObjectMapper objectMapper;
    private final int summaryChars;

    /**
     * @param llm          LLM 调用口（外部接 ChatClient，不可为 null）
     * @param objectMapper Jackson 序列化器
     * @param summaryChars 送进摘要的字幕字符上限，必须 &gt; 0
     * @throws IllegalArgumentException 任一参数非法
     */
    public SubtitleContextBuilder(Llm llm, ObjectMapper objectMapper, int summaryChars) {
        if (llm == null) {
            throw new IllegalArgumentException("Llm 不能为 null：请接 ChatClient");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("ObjectMapper 不能为 null");
        }
        if (summaryChars <= 0) {
            throw new IllegalArgumentException("摘要字符上限必须为正：" + summaryChars);
        }
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.summaryChars = summaryChars;
    }

    /**
     * 跑摘要，产出上下文。任何失败都降级为空上下文，不抛异常。
     *
     * @param segments  待纠错的分段（时间升序）
     * @param reference 人工术语表原文；本类不用它，保留参数是为了调用点整齐——摘要只看字幕本身
     * @return 上下文；失败时 {@link SubtitleContext#empty()}
     */
    public SubtitleContext build(List<TranscriptionSegment> segments, String reference) {
        String sample = sampleText(segments);
        if (sample.isBlank()) {
            return SubtitleContext.empty();
        }
        String content;
        try {
            content = llm.complete("请分析以下字幕：\n" + sample);
        } catch (RuntimeException e) {
            // 摘要失败不影响纠错——降级为「没有上下文」，继续往下走
            log.warn("字幕摘要调用失败，本次纠错不带跨批上下文: reason={}", e.getMessage());
            return SubtitleContext.empty();
        }
        return parse(content);
    }

    /**
     * 取全篇开头若干字符作为摘要输入。
     *
     * <p>只取开头是有意的（照搬参考项目 VideoLingo 的 {@code summary_length: 8000}）：长视频
     * 全篇塞进去既贵又容易超窗。代价是结尾的内容不参与主题推断——真在数据上发现主题偏了再调。
     */
    private String sampleText(List<TranscriptionSegment> segments) {
        StringBuilder sb = new StringBuilder();
        for (TranscriptionSegment seg : segments) {
            sb.append(seg.text() == null ? "" : seg.text()).append('\n');
            if (sb.length() >= summaryChars) {
                break;
            }
        }
        return sb.length() > summaryChars ? sb.substring(0, summaryChars) : sb.toString();
    }

    /** 解析摘要回复；任何不合预期的形态都降级为空上下文。 */
    private SubtitleContext parse(String content) {
        if (content == null || content.isBlank()) {
            log.warn("字幕摘要回复为空，本次纠错不带跨批上下文");
            return SubtitleContext.empty();
        }
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(SubtitleCorrector.stripCodeFence(content).trim(),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (JacksonException e) {
            log.warn("字幕摘要回复不是合法 JSON，本次纠错不带跨批上下文: reason={}", e.getMessage());
            return SubtitleContext.empty();
        }
        String theme = root.get("theme") instanceof String t ? t : "";
        return new SubtitleContext(theme, toTerms(root.get("terms")));
    }

    /**
     * terms 字段 → 术语列表。逐项要求 {@code src}/{@code tgt} 都在（缺了这条就没法用），
     * {@code note} 缺失当空串。
     *
     * <p>不做「整体不合法就全丢」——一条坏术语不该把其余好的拖下水。
     */
    private static List<SubtitleContext.Term> toTerms(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<SubtitleContext.Term> terms = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object src = map.get("src");
            Object tgt = map.get("tgt");
            if (!(src instanceof String s) || !(tgt instanceof String t) || s.isBlank()) {
                continue;
            }
            Object note = map.get("note");
            terms.add(new SubtitleContext.Term(s, t, note instanceof String n ? n : ""));
        }
        return terms;
    }
}
