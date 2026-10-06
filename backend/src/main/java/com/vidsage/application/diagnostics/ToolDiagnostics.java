package com.vidsage.application.diagnostics;

import java.util.List;

/**
 * 外部工具诊断的出口：探探 yt-dlp / ffmpeg / ffprobe 在不在、在哪、什么版本。
 *
 * <p>端口定义在本层而不是 domain——它跟视频转写业务没关系，契约里没有一个领域词汇
 * （工具名、版本号、路径、可用与否），是环境自检 / 运维排障能力，端点只为让运维知道
 * 机器上工具装没装。
 *
 * <p>实现在 {@code infrastructure/process/}（当前是走本机进程的 {@code ProcessToolDiagnostics}），
 * 由 Spring 注进来。这是 infrastructure 反向依赖 application 的唯一入口——只为实现这个端口，
 * 不得反向调用本层的用例服务。
 */
public interface ToolDiagnostics {

    /**
     * 诊断全部外部工具（yt-dlp / ffmpeg / ffprobe）的存在性、解析到的绝对路径与版本，
     * yt-dlp 另含新鲜度告警。
     *
     * @return 三份报告，顺序固定（yt-dlp / ffmpeg / ffprobe）
     */
    List<ToolDiagnosticReport> diagnoseAll();
}
