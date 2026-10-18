package com.vidsage.infrastructure.provider.identity.bilibili;

import com.vidsage.domain.workspace.model.ResolvedMediaIdentity;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.port.MediaIdentityResolver;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * B 站链接的身份解析：把一条视频链接算成 {@code bvid|p}，认领同一个 {@code workspace}。
 *
 * <p>解析分两级：先<b>纯字符串</b>剥跟踪参数、取 {@code /video/} 段的 {@code BV...}（或把
 * {@code av} 号换算成 {@code BV...}）——这一级<b>不发任何网络请求</b>；只有连视频号都认不出
 * （如 {@code b23.tv} 短链）才跟一次跳转再解析。<b>跳转只跟一次，不递归</b>。
 *
 * <p>仍认不出时<b>回落规范化后的 URL 原文当身份键</b>，不抛异常——这是降级路径不是失败路径：
 * 非 B 站链接照样能提交，只是每次各成一份身份。
 *
 * <p>跟跳转的客户端<b>延迟到首次 {@code resolve} 才 {@code build()}</b>：构造时只把请求工厂
 * 挂到注入的 builder 上，好让测试能在真正发请求前替换掉那个工厂。
 */
@Slf4j
@Component
public class BilibiliMediaIdentityResolver implements MediaIdentityResolver {

    /** URL 里标记视频页的路径段，取它后面那一段当视频号。 */
    private static final String VIDEO_PATH_SEGMENT = "/video/";

    /** {@code av} 号形态（大小写都认）。 */
    private static final Pattern AV_PATTERN = Pattern.compile("av(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * 跟踪参数：只影响统计，不影响内容，剥掉才能让同一个视频算出同一个身份键。
     *
     * <p><b>{@code p} 绝不在这里</b>——它是身份键的一半（{@code bvid|p}）。
     */
    private static final Set<String> TRACKING_PARAMS = Set.of(
            "spm_id_from",   // 站内位置标记
            "vd_source",     // 来源渠道标记
            "trackid",       // 埋点跟踪号
            "from_source",   // 来源渠道
            "from_spmid",    // 来源位置标记
            "share_source",  // 分享来源
            "share_medium",  // 分享媒介
            "bbid",          // 浏览器标识
            "ts",            // 时间戳
            "unique_k");     // 分享短码

    /** av 号换算 BV 号用的字符表：58 个字符，索引即该位的值。 */
    private static final String BV_TABLE = "fZodR9XQDSUm21yCkr6zBqiveYah8bt4xsWpHnJE7jL5VG3guMTKNPAwcF";

    /** BV 串里被算出来的 6 个位（其余位是固定的 {@code BV1} 与 {@code 4/1/7}）。 */
    private static final int[] BV_POSITIONS = {11, 10, 3, 8, 4, 6};

    /** 换算第一步的异或常量。 */
    private static final long AV_XOR = 177451812L;

    /** 换算第一步的加常量。 */
    private static final long AV_ADD = 8728348608L;

    /** 换算结果的骨架：{@code BV1} 加固定位 {@code 4/1/7}，其余 6 位由字符表填。 */
    private static final char[] BV_TEMPLATE = {'B', 'V', '1', '0', '0', '4', '0', '1', '0', '7', '0', '0'};

    /** 58 的各次幂，用于按 58 进制取位（避免循环里反复求幂）。 */
    private static final long[] POW_58 = {1L, 58L, 3364L, 195112L, 11316496L, 656356768L};

    private final RestClient.Builder restClientBuilder;

    /**
     * 短链 → 长链映射的进程内有界缓存。访问序 LRU：满了淘汰最久没碰的，
     * 既不会无界增长，也不会在批量提交时整表抖动。
     */
    private final Map<String, String> shortLinkCache;

    /** 跟跳转用的客户端；首次用到才建，见 {@link #restClient()}。 */
    private volatile RestClient restClient;

    public BilibiliMediaIdentityResolver(RestClient.Builder restClientBuilder, BilibiliIdentityProperties properties) {
        this.restClientBuilder = restClientBuilder.requestFactory(redirectRequestFactory(properties));
        int cacheSize = properties.shortLinkCacheSize();
        this.shortLinkCache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > cacheSize;
            }
        });
    }

    @Override
    public ResolvedMediaIdentity resolve(String sourceUri) {
        if (!StringUtils.hasText(sourceUri)) {
            throw new IllegalArgumentException("url 不能为空：请传公网媒体页面地址（如 B 站视频链接）");
        }
        String url = sourceUri.trim();
        ResolvedMediaIdentity direct = parseVideoIdentity(url);
        if (direct != null) {
            return direct;
        }
        String expanded = expandShortLink(url);
        if (expanded != null) {
            ResolvedMediaIdentity afterRedirect = parseVideoIdentity(expanded);
            if (afterRedirect != null) {
                return afterRedirect;
            }
        }
        String normalized = normalize(url);
        log.warn("认不出 B 站视频号，回落规范化 URL 当身份键: host={}", hostOf(url));
        return new ResolvedMediaIdentity(new SourceKey(normalized), normalized);
    }

    /**
     * 纯字符串解析：剥跟踪参数、取视频号。认不出返回 {@code null}（由调用方决定是否跟跳转）。
     */
    private static ResolvedMediaIdentity parseVideoIdentity(String url) {
        URI uri = parseUri(url);
        if (uri == null) {
            return null;
        }
        String bvid = extractBvid(uri.getPath());
        if (bvid == null) {
            return null;
        }
        return new ResolvedMediaIdentity(new SourceKey(bvid + "|" + parsePage(uri.getRawQuery())), normalize(uri));
    }

    /**
     * 从路径里取视频号：{@code /video/} 段后面那一段，是 {@code BV...} 就原样返回，
     * 是 {@code av...} 就换算成 {@code BV...}，都不是返回 {@code null}。
     */
    private static String extractBvid(String path) {
        if (path == null) {
            return null;
        }
        int marker = path.indexOf(VIDEO_PATH_SEGMENT);
        if (marker < 0) {
            return null;
        }
        String rest = path.substring(marker + VIDEO_PATH_SEGMENT.length());
        int slash = rest.indexOf('/');
        String segment = slash < 0 ? rest : rest.substring(0, slash);
        if (segment.startsWith("BV") && segment.length() > 2) {
            return segment;
        }
        Matcher matcher = AV_PATTERN.matcher(segment);
        if (matcher.matches()) {
            return avToBv(Long.parseLong(matcher.group(1)));
        }
        return null;
    }

    /**
     * av 号换算成 BV 号。B 站两套号可互相换算：先把 av 号异或 {@link #AV_XOR} 再加 {@link #AV_ADD}，
     * 得到的数按 <b>58 进制</b>逐位查 {@link #BV_TABLE}，填进 BV 串的 {@link #BV_POSITIONS} 六个位
     * （其余位是固定的 {@code BV1} 与 {@code 4/1/7}）。取 58 进制是因为字符表正好 58 个字符。
     */
    private static String avToBv(long aid) {
        long encoded = (aid ^ AV_XOR) + AV_ADD;
        char[] result = BV_TEMPLATE.clone();
        for (int i = 0; i < BV_POSITIONS.length; i++) {
            result[BV_POSITIONS[i]] = BV_TABLE.charAt((int) ((encoded / POW_58[i]) % 58));
        }
        return new String(result);
    }

    /**
     * 取查询串里的 {@code p}（分 P 号），缺省与非法值都当 1。
     */
    private static int parsePage(String rawQuery) {
        if (rawQuery == null) {
            return 1;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !"p".equals(pair.substring(0, eq))) {
                continue;
            }
            try {
                int page = Integer.parseInt(pair.substring(eq + 1));
                return page >= 1 ? page : 1;
            } catch (NumberFormatException e) {
                return 1;
            }
        }
        return 1;
    }

    /**
     * 规范化：去掉跟踪参数，其余（scheme / host / path / 保留下来的查询参数 / fragment）原样保留。
     */
    private static String normalize(URI uri) {
        StringBuilder sb = new StringBuilder();
        if (uri.getScheme() != null) {
            sb.append(uri.getScheme()).append("://");
        }
        if (uri.getRawAuthority() != null) {
            sb.append(uri.getRawAuthority());
        }
        if (uri.getRawPath() != null) {
            sb.append(uri.getRawPath());
        }
        String query = stripTrackingParams(uri.getRawQuery());
        if (query != null) {
            sb.append('?').append(query);
        }
        if (uri.getRawFragment() != null) {
            sb.append('#').append(uri.getRawFragment());
        }
        return sb.toString();
    }

    private static String normalize(String url) {
        URI uri = parseUri(url);
        return uri == null ? url.trim() : normalize(uri);
    }

    /**
     * 去掉查询串里的跟踪参数，返回保留下来的部分；一个都没剩返回 {@code null}。
     */
    private static String stripTrackingParams(String rawQuery) {
        if (!StringUtils.hasText(rawQuery)) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            if (!TRACKING_PARAMS.contains(name)) {
                kept.add(pair);
            }
        }
        return kept.isEmpty() ? null : String.join("&", kept);
    }

    private static URI parseUri(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            return URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 展开短链：命中缓存直接用；没命中就跟一次跳转，成功才写缓存（失败不缓存，免得把偶发失败钉死）。
     */
    private String expandShortLink(String url) {
        String cached = shortLinkCache.get(url);
        if (cached != null) {
            return cached;
        }
        String expanded = requestRedirectLocation(url);
        if (expanded != null) {
            shortLinkCache.put(url, expanded);
        }
        return expanded;
    }

    /**
     * 跟一次跳转、只读 {@code Location} 头。客户端禁用了自动跳转，所以 3xx 会原样返回；
     * 拿不到地址或请求失败都返回 {@code null}（由调用方回落）。
     */
    private String requestRedirectLocation(String url) {
        try {
            ResponseEntity<Void> response = restClient().get()
                    .uri(URI.create(url))
                    .retrieve()
                    .toBodilessEntity();
            URI location = response.getHeaders().getLocation();
            if (location == null) {
                return null;
            }
            return URI.create(url).resolve(location).toString();
        } catch (RuntimeException e) {
            log.warn("短链跟跳转失败，回落规范化 URL: host={}, reason={}", hostOf(url), e.getMessage());
            return null;
        }
    }

    /**
     * 首次用到才建客户端：构造时只是把请求工厂挂到注入的 builder 上，
     * 这样外部（测试 / 本地桩）仍能在真正发请求前替换掉请求工厂。
     */
    private RestClient restClient() {
        RestClient client = this.restClient;
        if (client == null) {
            synchronized (this) {
                client = this.restClient;
                if (client == null) {
                    client = restClientBuilder.build();
                    this.restClient = client;
                }
            }
        }
        return client;
    }

    /**
     * 跟跳转用的请求工厂：禁用自动跳转（要自己读 {@code Location}），连接与读取都设超时。
     */
    private static JdkClientHttpRequestFactory redirectRequestFactory(BilibiliIdentityProperties properties) {
        Duration timeout = properties.shortLinkTimeout();
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        return factory;
    }

    /**
     * 日志用的主机名；解析不出来就报 {@code unknown-host}，不打印链接原值。
     */
    private static String hostOf(String url) {
        URI uri = parseUri(url);
        if (uri == null || uri.getHost() == null) {
            return "unknown-host";
        }
        return uri.getHost();
    }
}
