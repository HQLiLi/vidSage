package com.vidsage.infrastructure.provider.llm.termlookup.dashscope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vidsage.domain.subtitle.TermLookupCommand;
import com.vidsage.domain.subtitle.TermLookupResult;

import java.net.URI;
import java.util.List;

import com.vidsage.domain.subtitle.TermLookupProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * DashScope 联网查证实现：用模型自带的 {@code enable_search} 联网检索，从检索结果里提取
 * 目标语种的官方/通用译名。
 *
 * <p><b>为什么是裸调兼容端点而不是 Spring AI ChatClient</b>：{@code enable_search} 是 DashScope
 * 扩展参数，官方文档证实部分客户端兼容层不转发它（搜索静默失效、还不报错）；本类用 RestClient
 * 自拼 JSON（Jackson 序列化 record），发出去什么就是什么。也<b>因此不能走 dashscope-sdk-java</b>——
 * 它的 Jackson 与 Spring AI 同坐标冲突，pom 里已有禁用实测记录。
 *
 * <p><b>接地口径（09-29 任务定）</b>：搜索在供应商侧执行，本类拿不到原始检索摘要做逐字比对，
 * 只能做下限约束——模型报了译名，就必须同时给出「来源 URL + 来源页引文」，缺任一即按查不到弃权。
 * 「宁可不列，不可编造」与摘要层提示词同一基调：错一个译名会被下游当成依据，比漏一条危害大。
 *
 * <p>失败语义见 {@link TermLookupProvider}：查不到（含证据不全）返回 {@code none()} 不抛异常；
 * 调用失败、响应缺内容、content 不是合法 JSON 抛运行时异常，由上层按「该词未查证」降级。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "vidsage.term-lookup.provider", havingValue = "dashscope")
@EnableConfigurationProperties(DashScopeTermLookupProperties.class)
public class DashScopeTermLookupProvider implements TermLookupProvider {

    private static final String SYSTEM_PROMPT = """
            你是字幕术语查证助手。给你一个专有名词、它所在视频的主题、目标语言（BCP-47 短码）。
            你的任务：联网搜索查证这个名词在目标语言中的译名。

            判断原则：
            - 结论必须来自搜索结果，禁止凭你自己的记忆直接作答；搜索结果里找不到就如实报告查不到。
            - 官方译名（level=official）：官方渠道给出——发行商/制作方官网、官方商店页、官方公告。
            - 通用译名（level=common）：非官方，但多个独立来源写法一致。
            - 官方与通用都找不到、或你无法确定时，level=none 且 officialName 留空字符串。
              宁可不列，不可编造：凭记忆「想当然」给出的译名几乎必然是错的，而它会被当成依据。

            只输出一个 JSON 对象：
            {"officialName": "目标语言的译名，查不到则为空串", "level": "official 或 common 或 none", "sourceUrl": "证据来源页的URL，查不到则为空串", "quote": "来源页面里含该译名的原文引文，查不到则为空串"}
            不要输出任何解释、注释或 markdown 代码块。""";

    private final RestClient restClient;
    private final DashScopeTermLookupProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 依赖全部走构造函数注入，保证可测试性（见 quality-guidelines）。
     */
    @Autowired
    public DashScopeTermLookupProvider(RestClient.Builder restClientBuilder,
                                       DashScopeTermLookupProperties properties,
                                       ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public TermLookupResult lookup(TermLookupCommand command) {
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new IllegalStateException("缺少 DashScope API key：请设置 vidsage.term-lookup.dashscope.api-key"
                    + " 或环境变量 DASHSCOPE_API_KEY");
        }
        if (command == null || !StringUtils.hasText(command.term()) || !StringUtils.hasText(command.targetLanguage())) {
            throw new IllegalArgumentException("查证入参不完整：term 与 targetLanguage 都不能为空白");
        }
        String requestJson = buildRequest(command);
        long start = System.currentTimeMillis();
        String responseJson = post(requestJson);
        LookupAnswer answer = parseResponse(responseJson);
        TermLookupResult result = validateEvidence(answer);
        log.info("术语译名查证: term={}, verdict={}, level={}, source={}, cost={}ms",
                command.term(), result.officialName().isEmpty() ? "无" : "有", answer.level(),
                describeSource(result.sourceUrl()), System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 来源只记 host：来源页是公开网页，但完整 URL 拼进日志既长又无必要，host 足够人工回查
     * （与翻译 service 侧 officialNote 的记法一致，同 logging-guidelines 脱敏精神）。
     */
    private static String describeSource(String sourceUrl) {
        if (!StringUtils.hasText(sourceUrl)) {
            return "无";
        }
        try {
            String host = URI.create(sourceUrl.trim()).getHost();
            return StringUtils.hasText(host) ? host : "无";
        } catch (RuntimeException e) {
            return "无";
        }
    }

    /**
     * 发一次查证请求，返回响应原文。
     *
     * <p>包级可见是测试缝：单测覆写它返回固样响应，不真发 HTTP（同 DashScopeAsrProvider 的做法）。
     *
     * @param requestJson 请求体 JSON
     * @return 响应体原文；HTTP 层失败时直接抛 RestClientException（运行时异常，上层降级）
     */
    String post(String requestJson) {
        return restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestJson)
                .retrieve()
                .body(String.class);
    }

    /**
     * 组装一次查证请求体。扩展参数（enable_search / search_options / enable_thinking）是
     * DashScope 专属，用 @JsonProperty 显式映射，序列化后原样出现在请求里。
     */
    private String buildRequest(TermLookupCommand command) {
        String user = "术语「" + command.term() + "」\n视频主题：" + command.theme()
                + "\n目标语言：" + command.targetLanguage();
        ChatRequest request = new ChatRequest(
                properties.model(),
                List.of(new ChatMessage("system", SYSTEM_PROMPT), new ChatMessage("user", user)),
                true,
                new SearchOptions(properties.searchStrategy()),
                properties.temperature(),
                false);
        return objectMapper.writeValueAsString(request);
    }

    /**
     * 解析 DashScope 响应，取出首条回复的 content 并按查证 JSON 解析。
     *
     * <p>包级可见：单测直接喂固样响应。
     *
     * @throws IllegalStateException 响应没有 choices / 没有正文 / 正文不是合法的查证 JSON
     */
    LookupAnswer parseResponse(String responseJson) {
        ChatResponse response;
        try {
            response = objectMapper.readValue(responseJson, ChatResponse.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("查证响应不是合法 JSON：" + e.getMessage(), e);
        }
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().get(0).message() == null) {
            throw new IllegalStateException("查证响应缺少 choices 或正文");
        }
        String content = response.choices().get(0).message().content();
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException("查证响应正文为空");
        }
        return parseContent(content);
    }

    /**
     * 把模型回复的正文解析成查证结论。
     *
     * <p>模型偶发把 JSON 包进 markdown 代码围栏，截取首个 <code>{</code> 到最后一个 <code>}</code>
     * 之间的部分再解析；截不出来或解析不了都算「回复不合法」——按查证失败抛 ISE（该词保持未查证，
     * 下次补跑），而不是当成「查不到」：编一个格式坏掉的回复与「确证没有」是两回事。
     *
     * <p>包级可见：单测直接喂正文。
     */
    LookupAnswer parseContent(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("查证回复里没有 JSON 对象");
        }
        try {
            return objectMapper.readValue(content.substring(start, end + 1), LookupAnswer.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("查证回复不是合法的查证 JSON：" + e.getMessage(), e);
        }
    }

    /**
     * 接地校验：有译名就必须有证据（合法 URL + 非空引文），缺任一按「查不到」弃权。
     *
     * <p>level 只决定「官方还是通用」的标注：声明 none 或译名空白 → 弃权；level 缺失/无法识别
     * 但证据齐全 → 按<b>通用</b>处理（保守降档，不冒充官方——数据有用，弃了可惜）。
     *
     * <p>包级可见：单测直接断言证据规则的各分支。
     */
    TermLookupResult validateEvidence(LookupAnswer answer) {
        String name = answer == null || answer.officialName() == null ? "" : answer.officialName().trim();
        String level = answer == null || answer.level() == null ? "" : answer.level().trim();
        if (name.isEmpty() || "none".equalsIgnoreCase(level)) {
            return TermLookupResult.none();
        }
        if (!isValidHttpUrl(answer.sourceUrl()) || !StringUtils.hasText(answer.quote())) {
            log.warn("查证结论缺证据（译名={}, level={}，URL 或引文缺失），按查不到弃权", name, level);
            return TermLookupResult.none();
        }
        boolean official = "official".equalsIgnoreCase(level);
        return new TermLookupResult(name, official, answer.sourceUrl().trim(), answer.quote().trim());
    }

    private static boolean isValidHttpUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && StringUtils.hasText(uri.getHost());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 请求体。字段名是 DashScope 扩展参数，@JsonProperty 显式落成 snake_case。
     */
    /**
     * 请求体。字段名是 DashScope 扩展参数，@JsonProperty 显式落成 snake_case。
     *
     * <p>{@code enableThinking} 固定 false：查证是「从检索结果做确定性抽取」，不是推理题——
     * 实测（2026-09-29，qwen3.7-flash）思考模式让单条 1.5s 涨到 20~45s，15 条术语把同步
     * 翻译接口拖到必超时，且思维链按输出计费；关掉后结论质量无损。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatRequest(String model, List<ChatMessage> messages,
                       @JsonProperty("enable_search") boolean enableSearch,
                       @JsonProperty("search_options") SearchOptions searchOptions,
                       double temperature,
                       @JsonProperty("enable_thinking") boolean enableThinking) {
    }

    record ChatMessage(String role, String content) {
    }

    record SearchOptions(@JsonProperty("search_strategy") String searchStrategy) {
    }

    /**
     * 响应体：只取用到的字段，未知字段忽略（上游加字段不能弄坏解析）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(List<Choice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String content) {
    }

    /**
     * 查证回复正文的形状（模型按 system 提示词约定输出）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LookupAnswer(@JsonProperty("officialName") String officialName, String level,
                        @JsonProperty("sourceUrl") String sourceUrl, String quote) {
    }
}
