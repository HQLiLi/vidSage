package com.vidsage.provider.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 外部工具（yt-dlp / ffmpeg / ffprobe）与进程执行器的配置。
 * application.yml 中的 vidsage.tools.* 会覆盖这里的 @DefaultValue 兜底值。
 *
 * @param ytDlpPath               yt-dlp 二进制路径；留空则从 PATH 解析。
 *                                <b>不自动更新</b>，过期只告警
 * @param ffmpegPath              ffmpeg 二进制路径；留空则从 PATH 解析。
 *                                <b>一处配置、两处使用</b>：既供 Java 抽音频，也作为
 *                                yt-dlp 的 --ffmpeg-location
 * @param ffprobePath             ffprobe 二进制路径；留空则从 PATH 解析
 * @param defaultTimeoutSeconds   外部命令默认超时（秒），调用方未显式指定时使用
 * @param ytDlpFreshnessWarnDays  yt-dlp 版本距今超过该天数即告警（不自动更新）
 */
@ConfigurationProperties(prefix = "vidsage.tools")
public record ExternalToolProperties(
        String ytDlpPath,
        String ffmpegPath,
        String ffprobePath,
        @DefaultValue("300") long defaultTimeoutSeconds,
        @DefaultValue("90") long ytDlpFreshnessWarnDays) {
}
