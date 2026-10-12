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
 * <p><b>为什么 {@code keywordsFingerprint} 跟这份字幕睡在同一个文件里</b>（2026-10-12 加，任务
 * {@code 10-11-subtitle-keyword-regenerate}）：它答的是「这份字幕是用哪份关键词生成的」，
 * 而清洗的断点判据要靠它答「现在该不该重跑」。另开一个文件就得面对「谁先写谁后写、
 * 写到一半崩了算谁的」这类跨文件一致性——那是最难测、半年后最容易回归的东西。
 * 指纹跟它描述的那份产物同生共死，就没有这个问题。
 *
 * <p>代价：原始产物（{@code segments-<route>.json}）与关键词无关，这一栏恒为 {@code null}；
 * 存量产物没有这个字段，Jackson 读出来也是 {@code null}，两者语义一致。
 *
 * @param route    字幕来源路线（与产物文件名里的 token 同源）
 * @param language 识别出的语种：云端实测可能为 {@code null}（见
 *                 research/asr-cloud-behavior.md §三），序列化落
 *                 {@code null} 而非空串，消费方（MVP-3）需判空
 * @param segments 逐句段落，时间升序；无语音时为<b>空列表</b>而非 {@code null}。
 *                 <b>空列表是合法产出</b>（纯音乐/无语音），不是错误，见设计决策 5
 * @param keywordsFingerprint 生成这份字幕时的关键词指纹（{@code SubtitleKeywords#computeFingerprint} 的值）；
 *                 与关键词无关的产物（原始转写）为 {@code null}
 */
public record SubtitleTrack(SubtitleRoute route, String language,
                            List<TranscriptionSegment> segments,
                            String keywordsFingerprint) {
}
