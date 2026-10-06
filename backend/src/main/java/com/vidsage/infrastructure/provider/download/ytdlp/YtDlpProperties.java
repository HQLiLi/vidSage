package com.vidsage.infrastructure.provider.download.ytdlp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * yt-dlp 下载实现的专用配置。
 * application.yml 中的 {@code vidsage.download.yt-dlp.*} 绑定到这里。
 *
 * @param cookiesFile 导出的 cookies 文件路径；<b>留空表示不使用 cookies</b>。
 *                    部分站点（或反爬拦截）需要登录态才能下载，届时把它指向导出的
 *                    Netscape 格式 cookies 文件。不要写浏览器 cookies 数据库路径，
 *                    那需要先导出成文件
 */
@ConfigurationProperties(prefix = "vidsage.download.yt-dlp")
public record YtDlpProperties(String cookiesFile) {
}
