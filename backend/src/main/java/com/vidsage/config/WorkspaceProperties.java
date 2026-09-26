package com.vidsage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 任务工作目录配置。
 *
 * <p>application.yml 中的 {@code vidsage.workspace.*} 绑定到这里；未配置时用 {@link DefaultValue}
 * 兜底，因此<b>没有该配置块也能加载 Spring 上下文</b>（配置块在后续步骤才补进 yml）。
 *
 * <p>本类带的 {@code config/} 目录是「跨领域装配类的落点」，按 directory-structure.md
 * 「目录随代码出现，不预建空目录」由它带出。
 *
 * @param root 工作目录根路径；留空或不配置时为 {@code ./data/works}。
 *             单任务的子目录由内容寻址生成（见 {@code WorkspaceService}），
 *             不要指向只读目录，也不要把已存在的业务数据放在这个根下
 */
@ConfigurationProperties(prefix = "vidsage.workspace")
public record WorkspaceProperties(@DefaultValue("./data/works") String root) {
}
