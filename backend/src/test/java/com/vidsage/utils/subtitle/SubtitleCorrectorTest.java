package com.vidsage.utils.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.model.asr.TranscriptionSegment;
import com.vidsage.model.subtitle.SubtitleContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * SubtitleCorrector 的纯单测：<b>不起 Spring 容器、不 mock ChatModel</b>——因为 LLM 调用被抽成了
 * {@link SubtitleCorrector.Llm} 函数式接口，这里用一个「按剧本吐回复」的桩去驱动 agent loop，验证
 * 守卫（键一致 / 相似度）、重试反馈与「全败保留原文」的兜底。
 */
class SubtitleCorrectorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double NORMAL = 0.7;
    private static final double SHORT = 0.3;

    /** 按剧本吐回复的桩：按调用顺序返回 preset，耗尽后返回一个「高质量兜底」合法 JSON。 */
    private static final class ScriptedLlm implements SubtitleCorrector.Llm {
        final List<String> script = new ArrayList<>();
        final List<String> userPrompts = new ArrayList<>();
        final List<String> systems = new ArrayList<>();
        int call;

        @Override
        public String complete(String system, String user) {
            systems.add(system);
            userPrompts.add(user);
            if (call < script.size()) {
                return script.get(call++);
            }
            return "{\"1\":\"兜底\"}";
        }
    }

    private static SubtitleCorrector corrector(ScriptedLlm llm, int maxSteps) {
        return new SubtitleCorrector(llm, JSON, 20, maxSteps, NORMAL, SHORT);
    }

    private static TranscriptionSegment seg(String text) {
        return new TranscriptionSegment(0, 5000, text);
    }

    @Test
    void validAnswerAdoptedOnFirstTry() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add("{\"1\":\"我防到这边露出\", \"2\":\"今天天气不错\"}");
        SubtitleCorrector c = corrector(llm, 3);
        List<TranscriptionSegment> in = List.of(seg("我防关这边露出"), seg("今天天气不错"));

        SubtitleCorrector.CorrectionResult r = c.correct(in, SubtitleContext.empty(), null);

        // 只改了错字的那条，未改的字原样保留；时间戳不变、条数不变
        assertEquals("我防到这边露出", r.segments().get(0).text());
        assertEquals("今天天气不错", r.segments().get(1).text());
        assertEquals(1, r.correctedCount());
        assertEquals(0, r.retrySteps());
        assertFalse(llm.userPrompts.isEmpty(), "应至少调了一次 LLM");
    }

    @Test
    void overRewritingTriggeredRetryThenAdopted() {
        ScriptedLlm llm = new ScriptedLlm();
        // 第 1 次过度改写（相似度远低于 0.7）→ 触发重试；第 2 次只改错字 → 采纳
        llm.script.add("{\"1\":\"我从这一侧郑剑保护自己周全\"}");
        llm.script.add("{\"1\":\"我防到这边露出\"}");
        SubtitleCorrector c = corrector(llm, 3);
        List<TranscriptionSegment> in = List.of(seg("我防关这边露出"));

        SubtitleCorrector.CorrectionResult r = c.correct(in, SubtitleContext.empty(), null);

        assertEquals("我防到这边露出", r.segments().get(0).text());
        assertEquals(1, r.retrySteps(), "第一次被拒后应恰好重试一次");
        // 反馈应该拼进了第 2 条 user 提示（含「改动过大」）
        assertTrue(llm.userPrompts.get(1).contains("改动过大"),
                "第 2 次提示应带上第 1 次校验失败的反馈，实际：" + llm.userPrompts.get(1));
    }

    @Test
    void allAttemptsInvalidKeepOriginal() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add("{\"1\":\"完全不同完全不同完全不同\"}");
        llm.script.add("{\"1\":\"再不同再不同再不同再不同\"}");
        SubtitleCorrector c = corrector(llm, 2);   // 只给 2 次机会，两次都不达标
        List<TranscriptionSegment> in = List.of(seg("我防关这边露出"));

        SubtitleCorrector.CorrectionResult r = c.correct(in, SubtitleContext.empty(), null);

        assertEquals("我防关这边露出", r.segments().get(0).text(), "全败时应保留原文（兜底不伤数据）");
        assertEquals(0, r.correctedCount());
        assertEquals(2, r.retrySteps(), "两次尝试都失败，重试数应为 2");
    }

    @Test
    void missingKeyTriggeredRetryThenRecovered() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add("{\"1\":\"我防到这边露出\"}");                // 缺了 "2"
        llm.script.add("{\"1\":\"我防到这边露出\", \"2\":\"今天天气不错\"}"); // 补全
        SubtitleCorrector c = corrector(llm, 3);
        List<TranscriptionSegment> in = List.of(seg("我防关这边露出"), seg("今天天气不错"));

        SubtitleCorrector.CorrectionResult r = c.correct(in, SubtitleContext.empty(), null);

        assertEquals(2, r.segments().size());
        assertEquals(1, r.retrySteps());
        assertTrue(llm.userPrompts.get(1).contains("缺少条目"),
                "第 2 次提示应带「缺少条目」反馈，实际：" + llm.userPrompts.get(1));
    }

    @Test
    void toleratesMarkdownCodeFenceInResponse() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add("```json\n{\"1\":\"我防到这边露出\"}\n```");
        SubtitleCorrector c = corrector(llm, 3);
        List<TranscriptionSegment> in = List.of(seg("我防关这边露出"));

        SubtitleCorrector.CorrectionResult r = c.correct(in, SubtitleContext.empty(), null);

        assertEquals("我防到这边露出", r.segments().get(0).text(), "应剥掉 ```json 围栏再解析");
        assertEquals(0, r.retrySteps());
    }

    @Test
    void referenceIsPassedIntoPromptWhenProvided() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add("{\"1\":\"我防到这边露出\"}");
        SubtitleCorrector c = corrector(llm, 3);
        c.correct(List.of(seg("我防关这边露出")), SubtitleContext.empty(), "术语表：纳尔人 = 捺尔人");

        assertTrue(llm.userPrompts.get(0).contains("纳尔人"),
                "提供了术语表时 user 提示应带上它，实际：" + llm.userPrompts.get(0));
    }

    @Test
    void validateAcceptsAndRejects() {
        SubtitleCorrector c = corrector(new ScriptedLlm(), 1);
        Map<String, String> original = Map.of("1", "我防关这边露出");
        // 合规：只改一字
        Map<String, String> ok = Map.of("1", "我防到这边露出");
        assertNull(c.validate(original, ok));
        // 违规：缺键
        assertTrue(c.validate(original, Map.of("2", "x")) != null);
        // 违规：过度改写
        Map<String, String> bad = Map.of("1", "我从这一侧郑剑保护自己周全");
        assertTrue(c.validate(original, bad) != null);
    }
}