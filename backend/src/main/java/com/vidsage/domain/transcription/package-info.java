/**
 * 转写上下文：语音转文字的领域契约——{@code AudioSource}（sealed：URL 或本地文件）、
 * 命令与结果 {@code TranscriptionCommand} / {@code TranscriptionResult} / {@code TranscriptionSegment}；
 * 端口接口 {@code ASRProvider}，由 infrastructure/provider/asr 的 DashScope 适配器实现。
 */
package com.vidsage.domain.transcription;
