package com.vidsage.infrastructure.process;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 单个外部工具的诊断报告。
 *
 * @param toolName  工具名（如 "yt-dlp" / "ffmpeg" / "ffprobe"）
 * @param available 是否找到可用二进制
 * @param version   解析到的版本号；<b>不可用或版本解析失败时为 {@code null}</b>
 * @param path      解析到的绝对路径；不可用时为 {@code null}
 * @param hint      提示语：不可用时为「缺什么 + 怎么补」；可用但版本过期时为新鲜度告警；
 *                  其余情况为 {@code null}
 */
@Schema(description = "单个外部工具的诊断报告")
public record ExternalToolReport(
        @Schema(description = "工具名", example = "yt-dlp")
        String toolName,
        @Schema(description = "是否找到可用二进制", example = "true")
        boolean available,
        @Schema(description = "解析到的版本号；不可用或版本解析失败时为 null", nullable = true,
                example = "2026.08.19")
        String version,
        @Schema(description = "解析到的绝对路径；不可用时为 null", nullable = true,
                example = "F:/vidsage/tools/yt-dlp.exe")
        String path,
        @Schema(description = "提示语：不可用时为「缺什么 + 怎么补」，版本过期时为新鲜度告警，其余为 null",
                nullable = true,
                example = "未找到 ffmpeg：请设置 vidsage.tools.ffmpeg-path 或加入 PATH")
        String hint) {
}
