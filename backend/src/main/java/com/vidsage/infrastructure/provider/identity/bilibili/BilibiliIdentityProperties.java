package com.vidsage.infrastructure.provider.identity.bilibili;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * B 站身份解析的专用配置。application.yml 中的 {@code vidsage.identity.bilibili.*} 绑定到这里。
 *
 * @param shortLinkTimeout   短链跟一次跳转的超时；连接与读取各用这个值
 * @param shortLinkCacheSize 进程内「短链 → 长链」缓存的条数上限；有界，防内存泄漏
 */
@ConfigurationProperties(prefix = "vidsage.identity.bilibili")
public record BilibiliIdentityProperties(
        @DefaultValue("2s") Duration shortLinkTimeout,
        @DefaultValue("256") int shortLinkCacheSize) {
}
