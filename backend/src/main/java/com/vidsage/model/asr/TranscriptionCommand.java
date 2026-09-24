package com.vidsage.model.asr;

/**
 * 转写请求命令。
 *
 * @param source   音频来源（URL 或本地路径，由各实现校验自己支持的类型）
 * @param language 可选的语种提示，如 "zh"；语种不确定或多语种混合时不要传
 */
public record TranscriptionCommand(AudioSource source, String language) {
}
