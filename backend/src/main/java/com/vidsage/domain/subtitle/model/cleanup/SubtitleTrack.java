package com.vidsage.domain.subtitle.model.cleanup;

import com.vidsage.domain.subtitle.model.common.SubtitleRoute;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import java.util.List;

/**
 * 一份字幕的全部内容：路线 + 语种 + 逐句段落。
 *
 * <p>**这份 record 序列化出来的 JSON 就是落盘的 {@code segments-<路线>.json} 的形状**——它是
 * MVP-3 翻译阶段的<b>唯一输入</b>，MVP-3 不需要再解析 SRT。形状即契约，改它要走接口变更流程。
 *
 * @param route    字幕来源路线（与产物文件名里的 token 同源）
 * @param language 识别出的语种：云端实测可能为 {@code null}（见
 *                 research/asr-cloud-behavior.md §三），序列化落
 *                 {@code null} 而非空串，消费方（MVP-3）需判空
 * @param segments 逐句段落，时间升序；无语音时为<b>空列表</b>而非 {@code null}。
 *                 <b>空列表是合法产出</b>（纯音乐/无语音），不是错误，见设计决策 5
 */
public record SubtitleTrack(SubtitleRoute route, String language,
                            List<TranscriptionSegment> segments) {
}