/**
 * 转写应用层：本上下文根包不留任何类，只有这份包说明——唯一的构件是管线阶段，住在 {@code pipeline/}。
 *
 * <p>{@code pipeline/} 装 {@code TranscriptionStage}：对上屏蔽 {@code ASRProvider} 的装配细节，
 * 把「音频来源 + 可选语种」组装为统一转写命令后委托当前配置的 Provider 实现，
 * 由上级管线按顺序调用一次。
 */
package com.vidsage.application.transcription;
