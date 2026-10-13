package com.vidsage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 任务工作目录配置。
 *
 * <p>application.yml 中的 {@code vidsage.workspace.*} 绑定到这里；未配置时用 {@link DefaultValue}
 * 兜底。
 *
 * @param root 工作目录根路径；留空或不配置时为 {@code ./data/works}。
 *             单任务的子目录由内容寻址生成（见 {@code WorkspaceDirectoryManager}），
 *             不要指向只读目录，也不要把已存在的业务数据放在这个根下
 */
@ConfigurationProperties(prefix = "vidsage.workspace")
public record WorkspaceProperties(@DefaultValue("./data/works") String root) {
}
