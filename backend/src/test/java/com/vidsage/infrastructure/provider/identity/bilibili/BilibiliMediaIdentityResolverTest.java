package com.vidsage.infrastructure.provider.identity.bilibili;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.vidsage.domain.workspace.model.ResolvedMediaIdentity;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link BilibiliMediaIdentityResolver} 的纯单元测试：不起 Spring 容器。
 *
 * <p>短链跳转用 Spring 官方的 {@code MockRestServiceServer} 打桩（绑到注入的
 * {@code RestClient.Builder} 上）；长链不该发任何请求。
 */
class BilibiliMediaIdentityResolverTest {

    private static final String BV = "BV11QMd6WEJp";

    private static final String LONG_URL = "https://www.bilibili.com/video/" + BV;

    private static final String SHORT_URL = "https://b23.tv/abc123";

    private BilibiliMediaIdentityResolver resolver;

    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        // 先建 resolver（它把跟跳转的请求工厂挂到 builder 上），再绑打桩服务器：
        // 打桩的工厂后设、覆盖前者，于是 resolve 里的请求都走打桩
        resolver = new BilibiliMediaIdentityResolver(builder,
                new BilibiliIdentityProperties(Duration.ofSeconds(2), 256));
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    void resolvesSameIdentityAcrossTrackingParams() {
        ResolvedMediaIdentity withSpm = resolver.resolve(LONG_URL + "?spm_id_from=333.999");
        ResolvedMediaIdentity withVd = resolver.resolve(LONG_URL + "?vd_source=abcdef");

        assertEquals(BV + "|1", withSpm.sourceKey().value());
        assertEquals(withSpm.sourceKey(), withVd.sourceKey());
    }

    @Test
    void readsExplicitPageParam() {
        ResolvedMediaIdentity identity = resolver.resolve(LONG_URL + "?p=3");

        assertEquals(BV + "|3", identity.sourceKey().value());
    }

    @Test
    void convertsAvNumberToBvid() {
        ResolvedMediaIdentity identity = resolver.resolve("https://www.bilibili.com/video/av170001");

        assertEquals("BV17x411w7KC|1", identity.sourceKey().value());
    }

    @Test
    void stripsTrackingParamsFromCanonicalUrl() {
        ResolvedMediaIdentity identity = resolver.resolve(LONG_URL + "?p=2&spm_id_from=x&vd_source=y");

        assertEquals(LONG_URL + "?p=2", identity.canonicalUrl());
        assertEquals(BV + "|2", identity.sourceKey().value());
    }

    @Test
    void expandsShortLinkThroughOneRedirect() {
        server.expect(requestTo(SHORT_URL))
                .andRespond(withStatus(HttpStatus.FOUND).location(URI.create(LONG_URL + "?spm_id_from=x")));

        ResolvedMediaIdentity identity = resolver.resolve(SHORT_URL);

        assertEquals(BV + "|1", identity.sourceKey().value());
        server.verify();
    }

    @Test
    void shortLinkExpansionIsCachedSoSecondResolveNeedsNoRequest() {
        server.expect(requestTo(SHORT_URL))
                .andRespond(withStatus(HttpStatus.FOUND).location(URI.create(LONG_URL)));

        resolver.resolve(SHORT_URL);
        ResolvedMediaIdentity second = resolver.resolve(SHORT_URL);

        assertEquals(BV + "|1", second.sourceKey().value());
        server.verify();
    }

    @Test
    void longLinkMakesNoNetworkRequest() {
        // 不打任何桩：若解析长链时发了请求，MockRestServiceServer 会当场抛「Unexpected request」
        ResolvedMediaIdentity identity = resolver.resolve(LONG_URL + "?spm_id_from=x");

        assertEquals(BV + "|1", identity.sourceKey().value());
        server.verify();
    }

    @Test
    void fallsBackToNormalizedUrlWhenVideoIdCannotBeResolved() {
        String unknown = "https://example.com/watch/42?spm_id_from=x&vd_source=y";
        server.expect(requestTo(unknown)).andRespond(withStatus(HttpStatus.OK));

        ResolvedMediaIdentity identity = resolver.resolve(unknown);

        assertEquals("https://example.com/watch/42", identity.sourceKey().value());
    }

    @Test
    void fallsBackWhenShortLinkRedirectCannotBeFollowed() {
        server.expect(requestTo(SHORT_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        ResolvedMediaIdentity identity = resolver.resolve(SHORT_URL);

        assertEquals(SHORT_URL, identity.sourceKey().value());
    }

    @Test
    void rejectsBlankUrl() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("  "));
    }
}
