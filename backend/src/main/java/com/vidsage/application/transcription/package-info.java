/**
 * 转写管线构件：{@code TranscriptionStage} 对上屏蔽 {@code ASRProvider} 的装配细节，
 * 把「音频来源 + 可选语种」组装为统一转写命令后委托当前配置的 Provider 实现。
 */
package com.vidsage.application.transcription;
