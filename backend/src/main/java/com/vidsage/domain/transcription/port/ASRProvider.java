package com.vidsage.domain.transcription.port;

import com.vidsage.domain.media.model.AudioSpec;
import com.vidsage.domain.subtitle.model.common.SubtitleRoute;
import com.vidsage.domain.transcription.model.TranscriptionCommand;
import com.vidsage.domain.transcription.model.TranscriptionResult;

/**
 * 语音识别供应商抽象：本地 FunASR 与云端 DashScope 各提供一个实现，
 * 通过 vidsage.asr.provider 配置切换。
 *
 * <p>除转写外，本接口还让实现<b>自述两个运行需求</b>（设计决策 1）：
 * 它<b>要什么音频格式</b>，以及它产出的是<b>哪条字幕路线</b>。目的都是把
 * 「实现相关的判断」从管线里收进实现自身，避免新增实现时忘记改映射（默认静默取错）。
 */
public interface ASRProvider {

    /**
     * 对给定音频执行一次转写。
     *
     * <p>实现方决定各字段的支持程度（如云端实现要求 audioUrl 公网可访问），
     * 上层管线只依赖本接口与本包 model 下的契约，不感知具体供应商。
     *
     * <p><b>转写成功 ≠ 一定有内容</b>：无语音音频可能返回空 segments 的合法结果
     * （见设计决策 5 与 {@code DashScopeAsrProvider} 的错误码降级）。
     * 调用方不要把「空 segments」当成外部调用失败。
     *
     * @param command 转写请求
     * @return 句级毫秒时间戳的转写结果，segments 不为 {@code null}；无语音时为合法空列表
     * @throws IllegalArgumentException 入参不合法（如音频来源为空）
     * @throws IllegalStateException 配置缺失或外部服务未按预期返回
     */
    TranscriptionResult transcribe(TranscriptionCommand command);

    /**
     * 本实现需要什么音频规格。上层据此向音频准备阶段要对应格式的音频，
     * 不在管线里写死容器名。
     *
     * @return 本实现要求的音频规格（如云端实现要求 {@code AudioSpec.M4A_AAC_48K_MONO}）
     */
    AudioSpec requiredAudioSpec();

    /**
     * 本实现产出的是哪条字幕路线。
     *
     * @return 本实现对应的字幕来源路线标记（如云端实现 {@code SubtitleRoute.ASR_CLOUD}）
     */
    SubtitleRoute route();
}
