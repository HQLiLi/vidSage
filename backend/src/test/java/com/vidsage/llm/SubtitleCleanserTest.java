package com.vidsage.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.config.SubtitleCleanupProperties;
import com.vidsage.model.asr.TranscriptionSegment;
import com.vidsage.model.subtitle.SubtitleContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * {@link SubtitleCleanser} 的纯单元测试：<b>不起 Spring 容器</b>，也不打网络。
 *
 * <p>手法是「真 {@code ChatClient} + 桩 {@code ChatModel}」：装一个只回剧本台词的 {@code ChatModel}，
 * 把 Spring AI 真正发出去的 {@link Prompt} 抓下来断言。这样既走到了真实的组装流程
 * （{@code defaultSystem} 怎么落、消息怎么排），又不依赖模型。
 *
 * <p>重点钉的是<b>重试的多轮消息形态</b>：模型上次的回复要作为 assistant 消息留在历史里、
 * 后面再追一条 user 反馈，而不是把反馈拼成文本重发同一条提示词。
 */
class SubtitleCleanserTest {

    private static final String SYS = "SYS-纠错指令";

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

    /** 默认调参：单批 20 句 / 600 字 / 跨批历史上限 20000。 */
    private static final SubtitleCleanupProperties PROPS =
            new SubtitleCleanupProperties(8000L, 600, 20, 3, 0.7, 0.3, 20000);

    private static SubtitleCleanser cleanserOver(ScriptedChatModel model) {
        return cleanserOver(model, PROPS);
    }

    private static SubtitleCleanser cleanserOver(ScriptedChatModel model, SubtitleCleanupProperties props) {
        return new SubtitleCleanser(ChatClient.builder(model).defaultSystem(SYS).build(), props);
    }

    /** 一批一句，用来造多批场景；跨批历史上限由用例指定。 */
    private static SubtitleCleanupProperties onePerBatch(int historyMaxChars) {
        return new SubtitleCleanupProperties(8000L, 600, 1, 3, 0.7, 0.3, historyMaxChars);
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

    @Test
    void 首轮只有一条任务消息_格式说明钉在它后面() {
        ScriptedChatModel model = new ScriptedChatModel(
                "{\"1\": \"今天天气很好\", \"2\": \"我们去公园\"}");
        SubtitleCleanser.CleanseResult result = cleanserOver(model)
                .clean(segments("今天天气很号", "我们去公园"), SubtitleContext.empty(), "");

        assertEquals(1, result.correctedCount(), "应有一条被修正");
        assertEquals(0, result.retrySteps(), "一次过，不该有重试");

        Prompt prompt = model.prompts.get(0);
        List<Message> messages = prompt.getInstructions();
        assertEquals(2, messages.size(), "system + 一条 user");
        assertEquals(SYS, messages.get(0).getText(), "defaultSystem 必须还在");
        String task = messages.get(1).getText();
        assertTrue(task.contains("今天天气很号"), "任务消息要含待修正字幕");
        assertTrue(task.contains("Your response should be in JSON format."),
                "格式说明要拼在任务这条消息里");
    }

    @Test
    void 重试时把模型上次的回复作为assistant消息留在历史里() {
        String bad = "{\"1\": \"今天天气很好\"}";          // 掉了 2、3 号，守卫会打回
        ScriptedChatModel model = new ScriptedChatModel(
                bad, "{\"1\": \"今天天气很好\", \"2\": \"我们去公园\", \"3\": \"好吗\"}");

        SubtitleCleanser.CleanseResult result = cleanserOver(model)
                .clean(segments("今天天气很号", "我们去公园", "好吗"), SubtitleContext.empty(), "");

        assertEquals(1, result.retrySteps(), "第一轮该被打回");
        assertEquals(2, model.prompts.size(), "共两次请求");

        List<Message> second = model.prompts.get(1).getInstructions();
        assertEquals(4, second.size(), "system + 任务 + 上次回复 + 反馈");
        assertEquals(SYS, second.get(0).getText());
        assertEquals(UserMessage.class, second.get(1).getClass());
        assertEquals(AssistantMessage.class, second.get(2).getClass(), "模型上次的回复要留在历史里");
        assertEquals(bad, second.get(2).getText(), "assistant 消息应是上次回复的原文");
        assertTrue(second.get(3).getText().contains("缺少条目"),
                "反馈要说清是哪里没过校验");
    }

    @Test
    void 连模型回复的原文都拿不到时_不伪造assistant轮次() {
        ScriptedChatModel model = new ScriptedChatModel("", "", "");   // 每轮都回空

        SubtitleCleanser.CleanseResult result = cleanserOver(model)
                .clean(segments("今天天气很号"), SubtitleContext.empty(), "");

        assertEquals("今天天气很号", result.segments().get(0).text(), "全败要保留原文");
        assertEquals(3, result.retrySteps(), "maxSteps=3，三轮都该算重试");
        List<Message> second = model.prompts.get(1).getInstructions();
        assertEquals(3, second.size(), "没有原文就不塞 assistant，只追加反馈");
        assertNotNull(second.get(2).getText());
    }

    @Test
    void 模型套了markdown围栏也照样解析() {
        // .entity() 换成自己持有的 BeanOutputConverter 后，剥围栏这步改由 convert() 内部的清洗链负责，
        // 喂一条围栏回复确认这条路还通
        ScriptedChatModel model = new ScriptedChatModel(
                "```json\n{\"1\": \"今天天气很好\"}\n```");

        SubtitleCleanser.CleanseResult result = cleanserOver(model)
                .clean(segments("今天天气很号"), SubtitleContext.empty(), "");

        assertEquals(1, result.correctedCount(), "围栏应被剥掉，JSON 应解析成功");
        assertEquals(0, result.retrySteps(), "解析成功就不该重试");
    }

    @Test
    void 重试用尽仍不合法_保留原文() {
        ScriptedChatModel model = new ScriptedChatModel("不是 JSON", "还是不是", "依然不是");

        SubtitleCleanser.CleanseResult result = cleanserOver(model)
                .clean(segments("今天天气很号", "我们去公园"), SubtitleContext.empty(), "");

        assertEquals(0, result.correctedCount(), "全败不该有任何改动");
        assertEquals("今天天气很号", result.segments().get(0).text());
        assertEquals("我们去公园", result.segments().get(1).text());
        assertEquals(3, result.retrySteps(), "三个轮次全部算重试");
        assertEquals(3, model.prompts.size(), "maxSteps=3，最多请求三次");
    }

    // ------------------------------------------------------------------
    // 跨批消息历史
    // ------------------------------------------------------------------

    @Test
    void 第二批的请求里能看见第一批的原文与已采纳的结果() {
        ScriptedChatModel model = new ScriptedChatModel(
                "{\"1\": \"今天天气很好\"}",
                "{\"1\": \"我们去公园\"}");

        SubtitleCleanser.CleanseResult result = cleanserOver(model, onePerBatch(20000))
                .clean(segments("今天天气很号", "我们去公园"), SubtitleContext.empty(), "");

        assertEquals(1, result.correctedCount());
        assertEquals(2, model.prompts.size(), "一批一句，共两次请求");
        assertEquals(2, model.prompts.get(0).getInstructions().size(), "第一批还没有历史");

        List<Message> second = model.prompts.get(1).getInstructions();
        assertEquals(4, second.size(), "system + 第一批的一对往返 + 本批任务");
        assertEquals(UserMessage.class, second.get(1).getClass());
        assertTrue(second.get(1).getText().contains("今天天气很号"), "历史里的 user 是第一批原文");
        assertEquals(AssistantMessage.class, second.get(2).getClass());
        assertEquals("{\"1\": \"今天天气很好\"}", second.get(2).getText(),
                "历史里的 assistant 是第一批被采纳的回复原文");
        assertEquals(UserMessage.class, second.get(3).getClass());
        assertTrue(second.get(3).getText().contains("我们去公园"), "最后一条才是本批任务");
    }

    @Test
    void 格式说明只钉在当前任务上_不进历史() {
        ScriptedChatModel model = new ScriptedChatModel(
                "{\"1\": \"今天天气很好\"}",
                "{\"1\": \"我们去公园\"}");
        cleanserOver(model, onePerBatch(20000))
                .clean(segments("今天天气很号", "我们去公园"), SubtitleContext.empty(), "");

        List<Message> second = model.prompts.get(1).getInstructions();
        long withFormat = second.stream()
                .filter(m -> m.getText().contains("Your response should be in JSON format."))
                .count();
        assertEquals(1, withFormat, "格式说明整份请求只该出现一次");
        assertTrue(second.getLast().getText().contains("Your response should be in JSON format."),
                "而且要在最末那条任务消息上");
        assertFalse(second.get(1).getText().contains("Your response should be in JSON format."),
                "历史里那条 user 不该带格式说明");
    }

    @Test
    void 批内失败的重试往返不进跨批历史() {
        String bad = "{\"1\": \"今天天气很好\"}";   // 掉了 2 号，会被打回
        String good = "{\"1\": \"今天天气很好\", \"2\": \"我们去公园\"}";
        ScriptedChatModel model = new ScriptedChatModel(
                bad, good, "{\"1\": \"好吗\"}");

        // batchSize=2 → 第一批 [1,2]、第二批 [3]
        cleanserOver(model, new SubtitleCleanupProperties(8000L, 600, 2, 3, 0.7, 0.3, 20000))
                .clean(segments("今天天气很号", "我们去公园", "好吗"), SubtitleContext.empty(), "");

        assertEquals(3, model.prompts.size(), "第一批重试一次 + 第二批一次");
        List<Message> third = model.prompts.get(2).getInstructions();
        assertEquals(4, third.size(), "历史只收被采纳的那次；若为 6 说明失败往返漏进了历史");
        assertEquals(good, third.get(2).getText(), "历史里的 assistant 应是成功那次的回复");
    }

    @Test
    void 重试用尽回滚原文的批次不进历史() {
        ScriptedChatModel model = new ScriptedChatModel(
                "不是 JSON", "还是不是", "依然不是", "{\"1\": \"好吗\"}");

        SubtitleCleanser.CleanseResult result = cleanserOver(model,
                        new SubtitleCleanupProperties(8000L, 600, 2, 3, 0.7, 0.3, 20000))
                .clean(segments("今天天气很号", "我们去公园", "好吗"), SubtitleContext.empty(), "");

        assertEquals(3, result.retrySteps());
        assertEquals("今天天气很号", result.segments().get(0).text(), "回滚批保留原文");
        assertEquals("我们去公园", result.segments().get(1).text());

        assertEquals(4, model.prompts.size(), "第一批 3 次 + 第二批 1 次");
        assertEquals(2, model.prompts.get(3).getInstructions().size(),
                "回滚批什么都没采纳，不该给历史留痕");
    }

    @Test
    void 后窗仍然注入_前窗已移除() {
        ScriptedChatModel model = new ScriptedChatModel(
                "{\"1\": \"今天天气很好\"}", "{\"1\": \"我们去公园\"}");
        cleanserOver(model, onePerBatch(20000))
                .clean(segments("今天天气很号", "我们去公园"), SubtitleContext.empty(), "");

        String first = allText(model.prompts.get(0));
        assertTrue(first.contains("<subsequent_content>"), "后窗要保留");
        assertTrue(first.contains("我们去公园"), "后窗内容是下一批开头");
        assertFalse(first.contains("<previous_content>"), "前窗已由跨批历史取代");

        assertFalse(allText(model.prompts.get(1)).contains("<previous_content>"),
                "第二批也不该再有前窗");
    }

    @Test
    void 历史超上限时丢掉最老的一轮往返() {
        List<Message> history = new ArrayList<>(List.of(
                new UserMessage("u1"), new AssistantMessage("a1"),
                new UserMessage("u2"), new AssistantMessage("a2"),
                new UserMessage("u3"), new AssistantMessage("a3")));   // 共 12 字符

        SubtitleCleanser.trimHistory(history, 9);   // 丢一对变 8 字符 ≤ 9，停

        assertEquals(4, history.size(), "只丢最老的一对");
        assertEquals("u2", history.getFirst().getText());
        assertEquals(UserMessage.class, history.getFirst().getClass(), "历史必须以 user 开头");
        assertEquals(AssistantMessage.class, history.getLast().getClass());
    }

    @Test
    void 只有一轮历史时即使超限也不丢() {
        List<Message> history = new ArrayList<>(List.of(
                new UserMessage("u1"), new AssistantMessage("a1")));

        SubtitleCleanser.trimHistory(history, 1);

        assertEquals(2, history.size(), "刚追加的那一对无论多长都要保留");
        assertEquals("u1", history.getFirst().getText());
    }

    @Test
    void 历史上限为0时关闭跨批历史() {
        ScriptedChatModel model = new ScriptedChatModel(
                "{\"1\": \"今天天气很好\"}", "{\"1\": \"我们去公园\"}");
        cleanserOver(model, onePerBatch(0))
                .clean(segments("今天天气很号", "我们去公园"), SubtitleContext.empty(), "");

        assertEquals(2, model.prompts.get(1).getInstructions().size(),
                "上限 0 = 关闭，第二批不该带历史");
    }
}
