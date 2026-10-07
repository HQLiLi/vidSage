/**
 * 转写上下文：语音转文字的领域词汇。根只剩本文件，构件按角色分进两个桶。
 *
 * <p>{@code model/} 装数据词汇——{@code AudioSource}（sealed：URL 或本地文件）、
 * 命令与结果 {@code TranscriptionCommand} / {@code TranscriptionResult} / {@code TranscriptionSegment}；
 * {@code port/} 装端口接口 {@code ASRProvider}，由 infrastructure/provider/asr 的 DashScope 适配器实现。
 */
package com.vidsage.domain.transcription;
