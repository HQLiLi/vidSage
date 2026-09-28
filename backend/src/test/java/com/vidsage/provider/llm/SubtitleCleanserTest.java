package com.vidsage.provider.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.config.SubtitleCleanupProperties;
import com.vidsage.model.asr.TranscriptionSegment;
import com.vidsage.model.subtitle.SubtitleContext;
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
            new SubtitleCleanupProperties(8000L, 600, 20, 3, 0.7, 0.3, 20000, false);

    private static SubtitleCleanser cleanserOver(ScriptedChatModel model) {
        return cleanserOver(model, PROPS);
    }

    private static SubtitleCleanser cleanserOver(ScriptedChatModel model, SubtitleCleanupProperties props) {
        return new SubtitleCleanser(ChatClient.builder(model).defaultSystem(SYS).build(), props);
    }

    /** 一批一句，用来造多批场景；跨批历史上限由用例指定。 */
    private static SubtitleCleanupProperties onePerBatch(int historyMaxChars) {
        return new SubtitleCleanupProperties(8000L, 600, 1, 3, 0.7, 0.3, historyMaxChars, false);
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
        cleanserOver(model, new SubtitleCleanupProperties(8000L, 600, 2, 3, 0.7, 0.3, 20000, false))
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
                        new SubtitleCleanupProperties(8000L, 600, 2, 3, 0.7, 0.3, 20000, false))
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

    // ------------------------------------------------------------------
    // 术语替换：校验层基线豁免 + 指标
    // ------------------------------------------------------------------

    /** 造一个带术语的上下文，主题固定；源语种留空（这批用例不关心它）。 */
    private static SubtitleContext contextWith(SubtitleContext.Term... terms) {
        return new SubtitleContext("剑术", "", List.of(terms));
    }

    @Test
    void 跨书写系统的术语替换不被判改太狠() {
        // 原文 15 字走 normal 阈值 0.7：直接比原文相似度 0.667 < 0.7 会被拦，
        // 靠「术语替换基线」把相似度拉回 1.0 才通过——正是本次故障的守卫反杀点
        ScriptedChatModel model = new ScriptedChatModel(
                "{\"1\": \"Mittelhau是其中记载最少的一种斩击\"}");
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("米特号", "Mittelhau", "ASR 把德语术语记成了中文音译"));

        SubtitleCleanser.CleanseResult result = cleanserOver(model)
                .clean(segments("米特号是其中记载最少的一种斩击"), ctx, "");

        assertEquals(0, result.retrySteps(), "正确的术语替换不该被判改太狠");
        assertEquals("Mittelhau是其中记载最少的一种斩击", result.segments().get(0).text());
        assertEquals(1, result.termHits(), "本批命中一条术语");
        assertEquals(1, result.termApplied(), "该术语已被替换");
    }

    @Test
    void 模型判断读不通而保留原写法_也不被拦() {
        // R2 把判断权交给模型：保留音译是正确结果，不能因「没改成基线」被拦
        ScriptedChatModel model = new ScriptedChatModel("{\"1\": \"这个展办得挺热闹\"}");
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("这个展", "这个斩", "同音误写"));

        SubtitleCleanser.CleanseResult result = cleanserOver(model)
                .clean(segments("这个展办得挺热闹"), ctx, "");

        assertEquals(0, result.retrySteps(), "保留原写法不该触发重试");
        assertEquals("这个展办得挺热闹", result.segments().get(0).text(), "应原样保留");
        assertEquals(0, result.correctedCount(), "没改动");
        assertEquals(1, result.termHits(), "命中了术语");
        assertEquals(0, result.termApplied(), "但没替换");
    }

    @Test
    void 长句保留原写法也不被拦_原文本身是合法目标() {
        // 14 字走 normal 阈值 0.7：若只跟基线比，保留原文相似度仅 0.43 会被判改太狠；
        // 取 max(原文, 基线) 后原文这条 1.0 兜住——R2 的「读不通就不换」靠这条守住，
        // 也钉住「不做强制」：不替换永不触发重试
        ScriptedChatModel model = new ScriptedChatModel(
                "{\"1\": \"乌波号与翁特号都是基础的斩击\"}");
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("乌波号", "Oberhau", "x"),
                new SubtitleContext.Term("翁特号", "Unterhau", "y"));

        SubtitleCleanser.CleanseResult result = cleanserOver(model)
                .clean(segments("乌波号与翁特号都是基础的斩击"), ctx, "");

        assertEquals(0, result.retrySteps(), "保留原文不该被判改太狠");
        assertEquals("乌波号与翁特号都是基础的斩击", result.segments().get(0).text(), "原样保留");
        assertEquals(2, result.termHits(), "两条命中");
        assertEquals(0, result.termApplied(), "一条都没换");
    }

    @Test
    void 只换一半也能过_指标如实上报() {
        // 只换「乌波号」那条：单比原文相似度 0.6875 < 0.7 会被拦，靠基线拉到 0.7317 ≥ 0.7 才过 ——
        // 部分替换是 R2 的自然结果，机器不逐词裁；但指标必须把「有一条没换」照出来，否则又是静默失败
        ScriptedChatModel model = new ScriptedChatModel(
                "{\"1\": \"Oberhau与翁特号都是基础的斩击\"}");
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("乌波号", "Oberhau", "x"),
                new SubtitleContext.Term("翁特号", "Unterhau", "y"));

        SubtitleCleanser.CleanseResult result = cleanserOver(model)
                .clean(segments("乌波号与翁特号都是基础的斩击"), ctx, "");

        assertEquals(0, result.retrySteps(), "部分替换应能过校验");
        assertEquals(2, result.termHits(), "命中两条术语");
        assertEquals(1, result.termApplied(), "只换了一条");
    }

    @Test
    void 全批不换时指标为零替换_命中仍计入() {
        ScriptedChatModel model = new ScriptedChatModel("{\"1\": \"乌波号与帕姆\"}");
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("乌波号", "Oberhau", "x"),
                new SubtitleContext.Term("帕姆", "Pommel", "y"));

        SubtitleCleanser.CleanseResult result = cleanserOver(model)
                .clean(segments("乌波号与帕姆"), ctx, "");

        assertEquals(2, result.termHits(), "两条命中");
        assertEquals(0, result.termApplied(), "一条都没换");
        assertEquals(0, result.retrySteps(), "不换也不该被拦");
    }

    @Test
    void asr与fixed相同的条目不进指标() {
        ScriptedChatModel model = new ScriptedChatModel("{\"1\": \"日耳曼剑术\"}");
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("日耳曼剑术", "日耳曼剑术", "本来就写对了"));

        SubtitleCleanser.CleanseResult result = cleanserOver(model)
                .clean(segments("日耳曼剑术"), ctx, "");

        assertEquals(0, result.termHits(), "无需替换的条目不该计入命中");
        assertEquals(0, result.termApplied());
    }

    @Test
    void 术语参考段按候选正确写法格式渲染() {
        ScriptedChatModel model = new ScriptedChatModel("{\"1\": \"米特号\"}");
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("米特号", "Mittelhau", "ASR 把德语术语记成了中文音译"));

        cleanserOver(model).clean(segments("米特号"), ctx, "");

        String task = allText(model.prompts.get(0));
        assertTrue(task.contains("### 术语参考"), "有命中就该带术语参考段");
        assertTrue(task.contains(
                        "- \"米特号\" → \"Mittelhau\"（候选正确写法）：ASR 把德语术语记成了中文音译"),
                "术语行应是「候选正确写法」格式，note 摆在显眼位置");
    }

    @Test
    void 只渲染本批正文里出现过的术语() {
        ScriptedChatModel model = new ScriptedChatModel("{\"1\": \"米特号\"}");
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("米特号", "Mittelhau", "x"),
                new SubtitleContext.Term("帕姆", "Pommel", "y"));

        cleanserOver(model).clean(segments("米特号"), ctx, "");

        String task = allText(model.prompts.get(0));
        assertTrue(task.contains("米特号"), "出现过的术语要渲染");
        assertFalse(task.contains("帕姆"), "没出现过的术语不该渲染");
    }

    // ------------------------------------------------------------------
    // validate 的失败分类（观测项：结构化分类，不靠文案前缀）
    // ------------------------------------------------------------------

    /** 只为直接调 {@code validate} 造个实例，不起容器也不发请求。 */
    private static SubtitleCleanser validator() {
        return cleanserOver(new ScriptedChatModel());
    }

    @Test
    void 校验通过时返回null() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("1", "今天天气很好");

        assertNull(validator().validate(original, new LinkedHashMap<>(original), List.of()));
    }

    @Test
    void 缺少条目归为KEY_SET() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("1", "今天天气很好");
        original.put("2", "我们去公园");
        Map<String, String> candidate = new LinkedHashMap<>();
        candidate.put("1", "今天天气很好");

        SubtitleCleanser.ValidationFailure failure = validator().validate(original, candidate, List.of());

        assertNotNull(failure);
        assertEquals(SubtitleCleanser.ValidationFailure.FailureKind.KEY_SET, failure.kind());
        assertTrue(failure.detail().contains("缺少条目 2"), "detail 要保留原有文案");
    }

    @Test
    void 多余条目也归为KEY_SET() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("1", "今天天气很好");
        Map<String, String> candidate = new LinkedHashMap<>();
        candidate.put("1", "今天天气很好");
        candidate.put("2", "多出来的一条");

        SubtitleCleanser.ValidationFailure failure = validator().validate(original, candidate, List.of());

        assertNotNull(failure);
        assertEquals(SubtitleCleanser.ValidationFailure.FailureKind.KEY_SET, failure.kind());
        assertTrue(failure.detail().contains("多余条目 2"));
    }

    @Test
    void 改动过大归为TOO_MUCH_EDIT() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("1", "今天天气很好我们去公园散步");   // 13 字，走 normal 阈值 0.7
        Map<String, String> candidate = new LinkedHashMap<>();
        candidate.put("1", "xyzxyzxyzxy");                // 与原文无公共字符，相似度 0

        SubtitleCleanser.ValidationFailure failure = validator().validate(original, candidate, List.of());

        assertNotNull(failure);
        assertEquals(SubtitleCleanser.ValidationFailure.FailureKind.TOO_MUCH_EDIT, failure.kind());
        assertTrue(failure.detail().contains("改动过大"), "detail 要保留原有文案");
    }
}
