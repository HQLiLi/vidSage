package com.vidsage.provider.llm.termlookup.dashscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vidsage.model.subtitle.TermLookupCommand;
import com.vidsage.model.subtitle.TermLookupResult;
import com.vidsage.provider.llm.termlookup.dashscope.DashScopeTermLookupProperties;
import com.vidsage.provider.llm.termlookup.dashscope.DashScopeTermLookupProvider;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/**
 * DashScopeTermLookupProvider 的纯单元测试：<b>不起 Spring 容器、不发 HTTP</b>。
 *
 * <p>HTTP 缝在包级可见的 {@link DashScopeTermLookupProvider#post(String)}：测试子类覆写它
 * 返回固样响应，同时把请求体抓出来断言扩展参数确实在请求里（enable_search / search_options
 * 是 DashScope 专属参数，兼容客户端层会丢——这正是本实现自拼 JSON 的原因，值得钉住）。
 */
class DashScopeTermLookupProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final DashScopeTermLookupProperties PROPS = new DashScopeTermLookupProperties(
            "test-key", "qwen-flash", "https://dashscope.aliyuncs.com/compatible-mode/v1", "standard", 0.1);

    private final StringBuilder capturedRequest = new StringBuilder();

    /** 抓请求体的桩：post 不真发 HTTP，把请求存下来、返回构造时给的固样响应。 */
    private DashScopeTermLookupProvider providerReturning(String responseJson) {
        // builder 只需让构造器把 baseUrl 链走通；post 已被覆写，restClient 永远不会被真正调用。
        // baseUrl(any(String.class))：baseUrl 有 String/URI 两个重载，裸 any() 会因歧义编译不过
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.baseUrl(any(String.class))).thenReturn(builder);
        return new DashScopeTermLookupProvider(builder, PROPS, JSON) {
            @Override
            String post(String requestJson) {
                capturedRequest.setLength(0);
                capturedRequest.append(requestJson);
                return responseJson;
            }
        };
    }

    private static String chatResponse(String content) {
        return "{\"choices\":[{\"message\":{\"content\":"
                + JSON.writeValueAsString(content) + "}}]}";
    }

    private TermLookupResult lookup(DashScopeTermLookupProvider provider) {
        return provider.lookup(new TermLookupCommand("只狼", "en", "游戏动作解析"));
    }

    @Test
    void requestCarriesExtensionParamsAndPrompt() {
        DashScopeTermLookupProvider p = providerReturning(chatResponse(
                "{\"officialName\":\"Sekiro\",\"level\":\"official\","
                        + "\"sourceUrl\":\"https://store.steampowered.com/app/814260\",\"quote\":\"Sekiro: Shadows Die Twice\"}"));
        lookup(p);

        JsonNode req = JSON.readTree(capturedRequest.toString());
        assertEquals("qwen-flash", req.get("model").asString());
        assertTrue(req.get("enable_search").asBoolean());
        assertEquals("standard", req.get("search_options").get("search_strategy").asString());
        // 抽取任务不要采样抖动（同词逐轮异结论），也不要思考链（单条 1.5s → 20~45s，同步接口必超时）
        assertEquals(0.1, req.get("temperature").asDouble(), 1e-9);
        assertFalse(req.get("enable_thinking").asBoolean());
        assertEquals("system", req.get("messages").get(0).get("role").asString());
        String user = req.get("messages").get(1).get("content").asString();
        assertTrue(user.contains("只狼"));
        assertTrue(user.contains("en"));
        assertTrue(user.contains("游戏动作解析"));
    }

    @Test
    void officialVerdictWithEvidence() {
        DashScopeTermLookupProvider p = providerReturning(chatResponse(
                "{\"officialName\":\" Sekiro: Shadows Die Twice \",\"level\":\"official\","
                        + "\"sourceUrl\":\"https://store.steampowered.com/app/814260\","
                        + "\"quote\":\"Sekiro: Shadows Die Twice\"}"));
        TermLookupResult r = lookup(p);
        assertEquals("Sekiro: Shadows Die Twice", r.officialName());
        assertTrue(r.official());
        assertEquals("https://store.steampowered.com/app/814260", r.sourceUrl());
        assertEquals("Sekiro: Shadows Die Twice", r.quote());
    }

    @Test
    void commonVerdictMapsToNonOfficial() {
        DashScopeTermLookupProvider p = providerReturning(chatResponse(
                "{\"officialName\":\"posture meter\",\"level\":\"common\","
                        + "\"sourceUrl\":\"https://sekiro.fandom.com/wiki/Posture\",\"quote\":\"posture meter\"}"));
        assertFalse(lookup(p).official());
    }

    @Test
    void noneVerdictReturnsEmpty() {
        DashScopeTermLookupProvider p = providerReturning(chatResponse(
                "{\"officialName\":\"\",\"level\":\"none\",\"sourceUrl\":\"\",\"quote\":\"\"}"));
        assertEquals(TermLookupResult.none(), lookup(p));
    }

    @Test
    void verdictWithoutEvidenceIsAbstained() {
        // 有译名但给不出合法 URL / 引文：接地校验必须拦下，按查不到弃权
        DashScopeTermLookupProvider p = providerReturning(chatResponse(
                "{\"officialName\":\"Sekiro\",\"level\":\"official\",\"sourceUrl\":\"不是URL\",\"quote\":\"引文\"}"));
        assertEquals(TermLookupResult.none(), lookup(p));

        DashScopeTermLookupProvider p2 = providerReturning(chatResponse(
                "{\"officialName\":\"Sekiro\",\"level\":\"official\","
                        + "\"sourceUrl\":\"https://store.steampowered.com\",\"quote\":\"\"}"));
        assertEquals(TermLookupResult.none(), lookup(p2));

        // level 缺失但证据齐全：保守降档为「通用」，不冒充官方，也不弃权（数据有用）
        DashScopeTermLookupProvider p3 = providerReturning(chatResponse(
                "{\"officialName\":\"Sekiro\","
                        + "\"sourceUrl\":\"https://store.steampowered.com\",\"quote\":\"引文\"}"));
        TermLookupResult r3 = lookup(p3);
        assertEquals("Sekiro", r3.officialName());
        assertFalse(r3.official());
    }

    @Test
    void fencedContentIsStripped() {
        DashScopeTermLookupProvider p = providerReturning(chatResponse(
                "```json\n{\"officialName\":\"Sekiro\",\"level\":\"official\","
                        + "\"sourceUrl\":\"https://store.steampowered.com/app/814260\",\"quote\":\"引文\"}\n```"));
        assertEquals("Sekiro", lookup(p).officialName());
    }

    @Test
    void garbageContentThrows() {
        DashScopeTermLookupProvider p = providerReturning(chatResponse("我觉得是 Sekiro 没错"));
        assertThrows(IllegalStateException.class, () -> lookup(p));
    }

    @Test
    void responseWithoutChoicesThrows() {
        DashScopeTermLookupProvider p = providerReturning("{\"error\":{\"message\":\"boom\"}}");
        assertThrows(IllegalStateException.class, () -> lookup(p));
    }

    @Test
    void missingApiKeyThrows() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.baseUrl(any(String.class))).thenReturn(builder);
        DashScopeTermLookupProvider p = new DashScopeTermLookupProvider(builder,
                new DashScopeTermLookupProperties(" ", "qwen-flash", "https://x", "standard", 0.1), JSON) {
            @Override
            String post(String requestJson) {
                return "{}";
            }
        };
        assertThrows(IllegalStateException.class,
                () -> p.lookup(new TermLookupCommand("只狼", "en", "")));
    }

    @Test
    void blankCommandThrows() {
        DashScopeTermLookupProvider p = providerReturning("{}");
        assertThrows(IllegalArgumentException.class,
                () -> p.lookup(new TermLookupCommand(" ", "en", "")));
        assertThrows(IllegalArgumentException.class,
                () -> p.lookup(new TermLookupCommand("只狼", " ", "")));
    }
}
