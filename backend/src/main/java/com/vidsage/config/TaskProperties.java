package com.vidsage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 异步任务管线配置。application.yml 中的 {@code vidsage.task.*} 绑定到这里；
 * 未配置时用 {@link DefaultValue} 兜底。
 *
 * @param maxConcurrent 同时执行的管线任务上限；超出者保持 PENDING 排队。必须 ≥ 1，默认 2
 */
@ConfigurationProperties(prefix = "vidsage.task")
public record TaskProperties(@DefaultValue("2") int maxConcurrent) {
}
