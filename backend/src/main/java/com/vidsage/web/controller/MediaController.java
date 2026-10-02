package com.vidsage.web.controller;

import com.vidsage.model.process.ExternalToolReport;
import com.vidsage.service.ToolDiagnosticsService;
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
 * <p>原「临时面」的同步端点（{@code POST /api/media/audio}、ASR / 字幕各端点）已随 MVP-1
 * 异步管线落地收编删除（见本任务 {@code research/acceptance-evidence.md}）——媒体与字幕的
 * 业务入口只有 {@code TaskController} 一处；本控制器只保留长久的诊断端点。
 *
 * <p>本类只做「接收 → 委托 → 返回」，不写业务逻辑；依赖 {@code service/}，不直接依赖 {@code provider/}。
 */
@Slf4j
@Tag(name = "媒体与音频", description = "外部工具诊断（只读）")
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final ToolDiagnosticsService toolDiagnosticsService;

    /**
     * 构造函数注入
     *
     * @param toolDiagnosticsService 工具诊断
     */
    public MediaController(ToolDiagnosticsService toolDiagnosticsService) {
        this.toolDiagnosticsService = toolDiagnosticsService;
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
    public List<ExternalToolReport> tools() {
        long start = System.currentTimeMillis();
        List<ExternalToolReport> reports = toolDiagnosticsService.diagnoseAll();
        log.info("工具诊断完成: available={}/{}, cost={}ms",
                reports.stream().filter(ExternalToolReport::available).count(), reports.size(),
                System.currentTimeMillis() - start);
        return reports;
    }
}
