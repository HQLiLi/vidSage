/**
 * 外部进程执行能力：运行 yt-dlp / ffmpeg 等命令行工具——
 * {@code ExternalProcessRunner}（执行器）、{@code ProcessOutcome}（执行出参）、
 * {@code ProcessToolDiagnostics}（工具可用性诊断，实现 {@code application/diagnostics/port/ToolDiagnostics}
 * 端口——出参 {@code ToolDiagnosticReport} 随端口落在 application 层）、
 * {@code ExternalToolProperties}（工具路径等配置）。
 *
 * <p>执行机制本身<b>不抽端口</b>：只有「在本机调外部程序」一种实现方式，不存在同一能力多来源的缝。
 */
package com.vidsage.infrastructure.process;
