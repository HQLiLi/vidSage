package com.vidsage.web.controller;

import com.vidsage.application.diagnostics.ToolDiagnosticReport;
import com.vidsage.application.diagnostics.port.ToolDiagnostics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部工具诊断 HTTP 入口（只读）：yt-dlp / ffmpeg / ffprobe 的可用性、解析路径与版本。
 *
 * <p>本类只做「接收 → 委托 → 返回」，不写业务逻辑；诊断能力经本层定义的出站端口
 * {@link ToolDiagnostics} 取得。
 */
@Slf4j
@Tag(name = "媒体与音频", description = "外部工具诊断（只读）")
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final ToolDiagnostics toolDiagnostics;

    public MediaController(ToolDiagnostics toolDiagnostics) {
        this.toolDiagnostics = toolDiagnostics;
    }

    /**
     * 诊断 yt-dlp / ffmpeg / ffprobe：可用性、解析到的绝对路径与版本；yt-dlp 额外报新鲜度。
     *
     * @return 三份工具诊断报告，顺序固定（yt-dlp / ffmpeg / ffprobe）
     */
    @Operation(summary = "诊断外部工具",
            description = "只读、秒级：返回 yt-dlp / ffmpeg / ffprobe 的可用性、解析到的绝对路径与版本；"
                    + "yt-dlp 版本过期时给出新鲜度告警（不自动更新）")
    @GetMapping("/tools")
    public List<ToolDiagnosticReport> tools() {
        long start = System.currentTimeMillis();
        List<ToolDiagnosticReport> reports = toolDiagnostics.diagnoseAll();
        log.info("工具诊断完成: available={}/{}, cost={}ms",
                reports.stream().filter(ToolDiagnosticReport::available).count(), reports.size(),
                System.currentTimeMillis() - start);
        return reports;
    }
}
