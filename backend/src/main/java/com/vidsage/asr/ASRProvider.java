package com.vidsage.asr;

import com.vidsage.asr.model.TranscriptionCommand;
import com.vidsage.asr.model.TranscriptionResult;

/**
 * 语音识别供应商抽象：本地 FunASR 与云端 DashScope 各提供一个实现，
 * 通过 vidsage.asr.provider 配置切换。
 */
public interface ASRProvider {

    /**
     * 对给定音频执行一次转写。
     *
     * <p>实现方决定各字段的支持程度（如云端实现要求 audioUrl 公网可访问），
     * 上层管线只依赖本接口与本包 model 下的契约，不感知具体供应商。
     *
     * @param command 转写请求
     * @return 句级毫秒时间戳的转写结果，segments 不为 {@code null}
     * @throws IllegalArgumentException 入参不合法（如音频来源为空）
     * @throws IllegalStateException 配置缺失或外部服务未按预期返回
     */
    TranscriptionResult transcribe(TranscriptionCommand command);
}
