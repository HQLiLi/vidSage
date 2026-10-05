package com.vidsage.infrastructure.provider.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.config.SubtitleTranslateProperties;
import com.vidsage.domain.transcription.TranscriptionSegment;
import com.vidsage.domain.subtitle.SubtitleContext;
import com.vidsage.domain.subtitle.VerifiedTerm;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * {@link SubtitleTranslator} 的纯单元测试：<b>不起 Spring 容器</b>，也不打网络。
 *
 * <p>手法对照 {@code SubtitleCleanserTest}：装一个只回剧本台词的 {@code ChatModel}，
 * 把 Spring AI 真正发出去的 {@link Prompt} 抓下来断言。
 *
 * <p>重点钉两件事：多轮重试的消息形态（失败往返只留在批内、不进跨批历史），
 * 以及「用尽重试回退原文并如实计数」这条可观测性。
 */
class SubtitleTranslatorTest {

    private static final String SYS = "SYS-翻译指令";

    /** 按剧本回话，并把每次收到的 {@link Prompt} 记下来。剧本用尽后回空串。 */
    static class ScriptedChatModel implements ChatModel {
        private final Deque<String> replies = new ArrayDeque<>();
        private final List<Prompt> prompts = new ArrayList<>();

        ScriptedChatModel(String... scriptedReplies) {
            for (String r : scriptedReplies) {
                replies.add(r);
            }
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            String reply = replies.isEmpty() ? "" : replies.poll();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }

    /** 默认调参：一批 12 句 / 400 字 / 跨批历史上限 20000。 */
    private static final SubtitleTranslateProperties PROPS =
            new SubtitleTranslateProperties("zh", 12, 400, 3, 20000);

    private static SubtitleTranslator translatorOver(ScriptedChatModel model) {
        return translatorOver(model, PROPS);
    }

    private static SubtitleTranslator translatorOver(ScriptedChatModel model,
                                                     SubtitleTranslateProperties props) {
        return new SubtitleTranslator(ChatClient.builder(model).defaultSystem(SYS).build(), props);
    }

    /** 一批一句，用来造多批场景；跨批历史上限由用例指定。 */
    private static SubtitleTranslateProperties onePerBatch(int historyMaxChars) {
        return new SubtitleTranslateProperties("zh", 1, 400, 3, historyMaxChars);
    }

    /** 把一份 {@link Prompt} 里所有消息的文本拼起来，用于「整份请求里有没有某段文字」这类断言。 */
    private static String allText(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (Message m : prompt.getInstructions()) {
            sb.append(m.getText()).append('\n');
        }
        return sb.toString();
    }

    private static List<TranscriptionSegment> segments(String... texts) {
        List<TranscriptionSegment> list = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            list.add(new TranscriptionSegment(i * 1000L, i * 1000L + 900, texts[i]));
        }
        return list;
    }

    // ------------------------------------------------------------------
    // 校验与重试
    // ------------------------------------------------------------------

    @Test
    void 正常翻译逐条产出_译文与原文不同() {
        ScriptedChatModel model = new ScriptedChatModel("{\"1\": \"Hello\"}");

        SubtitleTranslator.TranslateResult result = translatorOver(model)
                .translate(segments("你好"), SubtitleContext.empty(), "en");

        assertEquals(0, result.retrySteps(), "一次过，不该有重试");
        assertEquals(0, result.untranslatedCount());
        assertEquals(1, result.segments().size());
        assertEquals(0L, result.segments().get(0).beginTimeMs(), "时间轴原样带到产物");
        assertEquals(900L, result.segments().get(0).endTimeMs());
        assertEquals("你好", result.segments().get(0).sourceText());
        assertEquals("Hello", result.segments().get(0).translatedText());
    }

    @Test
    void 键集不一致触发重试() {
        String bad = "{\"1\": \"Hello\"}";                          // 掉了 2 号
        ScriptedChatModel model = new ScriptedChatModel(
                bad, "{\"1\": \"Hello\", \"2\": \"World\"}");

        SubtitleTranslator.TranslateResult result = translatorOver(model)
                .translate(segments("你好", "世界"), SubtitleContext.empty(), "en");

        assertEquals(1, result.retrySteps(), "第一轮该被打回");
        assertEquals(2, model.prompts.size(), "共两次请求");
        assertEquals("Hello", result.segments().get(0).translatedText());
        assertEquals("World", result.segments().get(1).translatedText());
        assertEquals(0, result.untranslatedCount(), "第二轮通过，不该有回退");

        List<Message> second = model.prompts.get(1).getInstructions();
        assertEquals(4, second.size(), "system + 任务 + 上次回复 + 反馈");
        assertEquals(AssistantMessage.class, second.get(2).getClass(), "模型上次的回复要留在历史里");
        assertEquals(bad, second.get(2).getText(), "assistant 消息应是上次回复的原文");
        assertTrue(second.get(3).getText().contains("缺少条目"), "反馈要说清是哪里没过校验");
    }

    @Test
    void 空译文触发重试() {
        ScriptedChatModel model = new ScriptedChatModel(
                "{\"1\": \"\", \"2\": \"World\"}",                  // 1 号给了空译文
                "{\"1\": \"Hello\", \"2\": \"World\"}");

        SubtitleTranslator.TranslateResult result = translatorOver(model)
                .translate(segments("你好", "世界"), SubtitleContext.empty(), "en");

        assertEquals(1, result.retrySteps(), "空译文该被打回");
        assertTrue(model.prompts.get(1).getInstructions().get(3).getText().contains("译文为空"),
                "反馈要指明是空译文");
        assertEquals("Hello", result.segments().get(0).translatedText());
    }

    @Test
    void 模型套了markdown围栏也照样解析() {
        ScriptedChatModel model = new ScriptedChatModel("```json\n{\"1\": \"Hello\"}\n```");

        SubtitleTranslator.TranslateResult result = translatorOver(model)
                .translate(segments("你好"), SubtitleContext.empty(), "en");

        assertEquals("Hello", result.segments().get(0).translatedText());
        assertEquals(0, result.retrySteps(), "解析成功就不该重试");
    }

    @Test
    void 用尽重试回退原文并计未翻译数() {
        ScriptedChatModel model = new ScriptedChatModel("不是 JSON", "还是不是", "依然不是");

        SubtitleTranslator.TranslateResult result = translatorOver(model)
                .translate(segments("你好", "世界"), SubtitleContext.empty(), "en");

        assertEquals(3, result.retrySteps(), "maxSteps=3，三轮都算重试");
        assertEquals(3, model.prompts.size(), "最多请求三次");
        assertEquals(2, result.untranslatedCount(), "整批回退，两条都算未翻译");
        assertEquals("你好", result.segments().get(0).translatedText(), "回退原文");
        assertEquals("世界", result.segments().get(1).translatedText());
    }

    // ------------------------------------------------------------------
    // 跨批消息历史
    // ------------------------------------------------------------------

    @Test
    void 第二批的请求里能看见第一批的原文与已采纳的译文() {
        ScriptedChatModel model = new ScriptedChatModel(
                "{\"1\": \"Hello\"}",
                "{\"1\": \"World\"}");

        translatorOver(model, onePerBatch(20000))
                .translate(segments("你好", "世界"), SubtitleContext.empty(), "en");

        assertEquals(2, model.prompts.size(), "一批一句，共两次请求");
        assertEquals(2, model.prompts.get(0).getInstructions().size(), "第一批还没有历史");

        List<Message> second = model.prompts.get(1).getInstructions();
        assertEquals(4, second.size(), "system + 第一批的一对往返 + 本批任务");
        assertEquals(AssistantMessage.class, second.get(2).getClass());
        assertTrue(second.get(1).getText().contains("你好"), "历史里的 user 是第一批原文");
        assertEquals("{\"1\": \"Hello\"}", second.get(2).getText(),
                "历史里的 assistant 是第一批被采纳的译文回复原文");
        assertTrue(second.get(3).getText().contains("世界"), "最后一条才是本批任务");
    }

    @Test
    void 批内失败的重试往返不进跨批历史() {
        String bad = "{\"1\": \"Hello\"}";                          // 掉了 2 号
        String good = "{\"1\": \"Hello\", \"2\": \"World\"}";
        ScriptedChatModel model = new ScriptedChatModel(bad, good, "{\"1\": \"Bye\"}");

        // batchSize=2 → 第一批 [你好, 世界]、第二批 [再会]
        translatorOver(model, new SubtitleTranslateProperties("zh", 2, 400, 3, 20000))
                .translate(segments("你好", "世界", "再会"), SubtitleContext.empty(), "en");

        assertEquals(3, model.prompts.size(), "第一批重试一次 + 第二批一次");
        List<Message> third = model.prompts.get(2).getInstructions();
        assertEquals(4, third.size(), "历史只收被采纳的那次；若为 6 说明失败往返漏进了历史");
        assertEquals(good, third.get(2).getText(), "历史里的 assistant 应是成功那次的回复");
    }

    @Test
    void 重试用尽回滚原文的批次不进历史() {
        ScriptedChatModel model = new ScriptedChatModel(
                "不是 JSON", "还是不是", "依然不是", "{\"1\": \"Bye\"}");

        translatorOver(model, new SubtitleTranslateProperties("zh", 2, 400, 3, 20000))
                .translate(segments("你好", "世界", "再会"), SubtitleContext.empty(), "en");

        assertEquals(4, model.prompts.size(), "第一批 3 次 + 第二批 1 次");
        assertEquals(2, model.prompts.get(3).getInstructions().size(),
                "回滚批什么都没采纳，不该给历史留痕");
    }

    @Test
    void 历史上限为0时关闭跨批历史() {
        ScriptedChatModel model = new ScriptedChatModel(
                "{\"1\": \"Hello\"}", "{\"1\": \"World\"}");

        translatorOver(model, onePerBatch(0))
                .translate(segments("你好", "世界"), SubtitleContext.empty(), "en");

        assertEquals(2, model.prompts.get(1).getInstructions().size(),
                "上限 0 = 关闭，第二批不该带历史");
    }

    // ------------------------------------------------------------------
    // 上下文注入
    // ------------------------------------------------------------------

    @Test
    void 术语参考只列本批正文里出现过的条目_渲染要保留的写法() {
        ScriptedChatModel model = new ScriptedChatModel("{\"1\": \"Mittelhau is basic\"}");
        // 翻译按 fixed 匹配（输入是清洗后文本），这里两条 fixed 都写死，只有 Mittelhau 出现
        SubtitleContext ctx = new SubtitleContext("剑术", "zh", List.of(
                new SubtitleContext.Term("Mittelhau", "Mittelhau", "本来就写对了"),
                new SubtitleContext.Term("Pommel", "Pommel", "y")));

        translatorOver(model).translate(segments("Mittelhau is basic"), ctx, "en");

        String task = allText(model.prompts.get(0));
        assertTrue(task.contains("### 术语参考"), "有命中就该带术语参考段");
        assertTrue(task.contains("Mittelhau"), "命中的术语要渲染");
        assertFalse(task.contains("Pommel"), "没出现过的术语不该渲染");
    }

    @Test
    void 清洗替换后的术语_翻译参考按fixed命中并渲染fixed写法() {
        // 清洗把「米特号」换成了「Mittelhau」，翻译吃到的正是这份 -clean 文本
        ScriptedChatModel model = new ScriptedChatModel("{\"1\": \"Mittelhau is basic\"}");
        SubtitleContext ctx = new SubtitleContext("剑术", "zh", List.of(
                new SubtitleContext.Term("米特号", "Mittelhau", "ASR 记成了中文音译")));

        translatorOver(model).translate(segments("Mittelhau is basic"), ctx, "en");

        String task = allText(model.prompts.get(0));
        assertTrue(task.contains("### 术语参考"), "按 fixed 命中，该带术语参考段");
        assertTrue(task.contains("Mittelhau"), "要保留的是 fixed 的写法");
        assertFalse(task.contains("米特号"), "渲染的是要保留的 fixed，不是 asr");
    }

    @Test
    void 查证到译名的术语_渲染成译名映射要求照翻() {
        ScriptedChatModel model = new ScriptedChatModel("{\"1\": \"Sekiro deflects\"}");
        SubtitleContext ctx = new SubtitleContext("剑术", "zh", List.of(
                new SubtitleContext.Term("只狼", "只狼", "游戏名")));
        List<VerifiedTerm> official = List.of(
                new VerifiedTerm("只狼", "只狼", "游戏名", "Sekiro: Shadows Die Twice", "官方译名"));

        translatorOver(model).translate(segments("只狼在弹刀"), ctx, "en", official);

        String task = allText(model.prompts.get(0));
        assertTrue(task.contains("### 术语参考"), "命中的查证术语要渲染");
        assertTrue(task.contains("- \"只狼\" → \"Sekiro: Shadows Die Twice\""),
                "查证译名要渲染成「词 → 译名」映射");
        assertTrue(task.contains("必须使用给定的译名"), "要说明按给定译名翻");
        assertFalse(task.contains("保留原样"), "有译名的词不再要求保留原样");
    }

    @Test
    void 弃权的查证术语_仍按保留原样渲染() {
        ScriptedChatModel model = new ScriptedChatModel("{\"1\": \"Deflect!\"}");
        SubtitleContext ctx = new SubtitleContext("剑术", "zh", List.of(
                new SubtitleContext.Term("弹刀", "弹刀", "机制名")));
        List<VerifiedTerm> official = List.of(
                new VerifiedTerm("弹刀", "弹刀", "机制名", "", "弃权"));

        translatorOver(model).translate(segments("弹刀很好用"), ctx, "en", official);

        String task = allText(model.prompts.get(0));
        assertTrue(task.contains("### 术语参考"), "弃权词也是术语，参考段还在");
        assertTrue(task.contains("- \"弹刀\""), "弃权词按保留原样渲染");
        assertTrue(task.contains("保留原样"), "弃权词维持保留原样指令");
        assertFalse(task.contains("→"), "弃权词没有译名映射");
    }

    @Test
    void 查证译名与弃权术语混合时各自渲染() {
        ScriptedChatModel model = new ScriptedChatModel("{\"1\": \"Sekiro deflects\"}");
        SubtitleContext ctx = new SubtitleContext("剑术", "zh", List.of(
                new SubtitleContext.Term("只狼", "只狼", "游戏名"),
                new SubtitleContext.Term("弹刀", "弹刀", "机制名")));
        List<VerifiedTerm> official = List.of(
                new VerifiedTerm("只狼", "只狼", "游戏名", "Sekiro: Shadows Die Twice", "官方译名"),
                new VerifiedTerm("弹刀", "弹刀", "机制名", "", "弃权"));

        translatorOver(model).translate(segments("只狼在弹刀"), ctx, "en", official);

        String task = allText(model.prompts.get(0));
        assertTrue(task.contains("- \"只狼\" → \"Sekiro: Shadows Die Twice\""), "有译名的走译名映射");
        assertTrue(task.contains("- \"弹刀\"\n"), "弃权的走保留原样，且不出现译名映射");
    }

    @Test
    void 后窗注入下一批开头几行_且不进本批正文() {
        ScriptedChatModel model = new ScriptedChatModel(
                "{\"1\": \"Hello\"}", "{\"1\": \"World\"}");

        translatorOver(model, onePerBatch(20000))
                .translate(segments("你好", "世界"), SubtitleContext.empty(), "en");

        String first = allText(model.prompts.get(0));
        assertTrue(first.contains("<subsequent_content>"), "后窗要注入");
        assertTrue(first.contains("世界"), "后窗内容是下一批开头");
    }

    @Test
    void 目标语种短码写进任务说明() {
        ScriptedChatModel model = new ScriptedChatModel("{\"1\": \"Hello\"}");

        translatorOver(model).translate(segments("你好"), SubtitleContext.empty(), "en");

        String task = allText(model.prompts.get(0));
        assertTrue(task.contains("BCP-47"), "要说明给的是短码");
        assertTrue(task.contains("en"), "目标语种短码要出现");
    }

    // ------------------------------------------------------------------
    // validate 直测（包级可见）
    // ------------------------------------------------------------------

    @Test
    void 校验通过时返回null() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("1", "你好");

        assertNull(SubtitleTranslator.validate(original, new LinkedHashMap<>(original)));
    }

    @Test
    void 缺少条目打回() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("1", "你好");
        original.put("2", "世界");
        Map<String, String> candidate = new LinkedHashMap<>();
        candidate.put("1", "Hello");

        String failure = SubtitleTranslator.validate(original, candidate);

        assertNotNull(failure);
        assertTrue(failure.contains("缺少条目 2"));
    }

    @Test
    void 空译文打回() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("1", "你好");
        Map<String, String> candidate = new LinkedHashMap<>();
        candidate.put("1", "   ");

        String failure = SubtitleTranslator.validate(original, candidate);

        assertNotNull(failure);
        assertTrue(failure.contains("译文为空"));
    }
}
