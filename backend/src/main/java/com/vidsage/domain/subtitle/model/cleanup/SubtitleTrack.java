package com.vidsage.domain.subtitle.model.cleanup;

import com.vidsage.domain.subtitle.model.common.SubtitleRoute;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import java.util.List;

/**
 * 一份字幕的全部内容：路线 + 语种 + 逐句段落。
 *
 * <p><b>这份 record 序列化出来的 JSON 就是落盘的 {@code segments-<路线>.json} 的形状</b>，
 * 也是翻译阶段唯一的输入；形状即契约，改它要走接口变更流程。{@code keywordsFingerprint}
 * 与它描述的那份字幕同睡一个文件——清洗的断点判据靠它答「现在该不该重跑」。
 *
 * @param route    字幕来源路线（与产物文件名里的 token 同源）
 * @param language 识别出的语种；云端实测可能为 {@code null}，消费方需判空
 * @param segments 逐句段落，时间升序；无语音时为<b>空列表</b>而非 {@code null}
 *                 ——空列表是合法产出（纯音乐/无语音），不是错误
 * @param keywordsFingerprint 生成这份字幕时的关键词指纹（{@code SubtitleKeywords#computeFingerprint} 的值）；
 *                 与关键词无关的产物（原始转写）与存量产物均为 {@code null}
 */
public record SubtitleTrack(SubtitleRoute route, String language,
                            List<TranscriptionSegment> segments,
                            String keywordsFingerprint) {
}
