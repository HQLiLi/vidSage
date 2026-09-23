package com.vidsage.asr;

/**
 * 语音识别供应商抽象：本地 FunASR 与云端 DashScope 各提供一个实现，
 * 通过 vidsage.asr.provider 配置切换。
 */
public interface ASRProvider {

    TranscriptionResult transcribe(TranscriptionCommand command);
}
