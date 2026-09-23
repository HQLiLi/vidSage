package com.vidsage.asr;

/**
 * 转写请求命令。
 *
 * @param audioUrl 公网可访问的音频文件 URL（DashScope 异步转写接口的要求）
 * @param language 可选的语种提示，如 "zh"；语种不确定或多语种混合时不要传
 */
public record TranscriptionCommand(String audioUrl, String language) {
}
