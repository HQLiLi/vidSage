package com.vidsage.application.subtitle;

import com.vidsage.domain.subtitle.model.ArtifactFileKind;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.subtitle.model.translation.TranslatedTrack;
import com.vidsage.shared.subtitle.SrtRenderer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 给一次产物下载算出「用户该看到的正文」：要套纠错的地方叠好重渲，不需要的地方返回空。
 *
 * <p><b>分流是按 {@code (产物类型, 文件位)} 静态判的，不猜</b>：
 * <ul>
 *   <li>ORIGINAL 全部、CLEANED 的 {@code context.json}、TRANSLATED 的 {@code terms.json}：
 *       与纠错无关，永远发磁盘文件；</li>
 *   <li>CLEANED 的 {@code segments.json} / {@code subtitles.srt}：叠原文纠错；</li>
 *   <li>TRANSLATED 的同两个文件位：只叠<b>该语种</b>的译文纠错。</li>
 * </ul>
 *
 * <p><b>「要不要重渲」看覆盖层有没有真的动过字幕</b>：{@link SubtitleCorrectionOverlay} 承诺
 * 「没有可叠的纠错就原样返回入参」，所以这里判 {@code corrected == machine} 就够了——不需要额外查库，
 * 也保证零纠错时下载走磁盘文件、与磁盘文件一个字节都不差。别把这句比较当成随手写的判断。
 *
 * <p>读不到磁盘上的机器产物时返回空（调用方直发文件）：下载不该因为叠加失败而断。
 * 读到了但序列化/渲染失败则如实上抛——静默退回未纠错版本等于给用户一份假的「已改」内容。
 */
@Slf4j
@Component
public class SubtitleArtifactRenderer {

    private final SubtitleTrackReader trackReader;

    private final SubtitleCorrectionOverlay overlay;

    private final ObjectMapper objectMapper;

    /**
     * @param trackReader   读磁盘上的 CLEANED / TRANSLATED 机器产物
     * @param overlay       纠错合并入口
     * @param objectMapper  与产物落盘同一份 Jackson 配置，重渲的 JSON 形状才不会漂
     */
    public SubtitleArtifactRenderer(SubtitleTrackReader trackReader, SubtitleCorrectionOverlay overlay,
                                    ObjectMapper objectMapper) {
        this.trackReader = trackReader;
        this.overlay = overlay;
        this.objectMapper = objectMapper;
    }

    /**
     * 算出这份下载该发的正文。
     *
     * @param taskId  字幕任务的内部行 id
     * @param type    产物类型
     * @param lang    目标语种短码（已归一）；只有 TRANSLATED 用得上，其余传空串
     * @param kind    文件位
     * @param workDir 工作区目录
     * @return 叠加纠错后的正文；不需要或没法重渲时为空（调用方直发磁盘文件）
     * @throws IllegalStateException 字幕读出来了、但序列化或渲染失败
     */
    public Optional<byte[]> render(long taskId, ArtifactType type, String lang, ArtifactFileKind kind,
                                   Path workDir) {
        return switch (type) {
            case ORIGINAL -> Optional.empty();
            case CLEANED -> renderCleaned(taskId, kind, workDir);
            case TRANSLATED -> renderTranslated(taskId, lang, kind, workDir);
        };
    }

    /**
     * 清洗产物：只有 segments / subtitles 两个文件位要叠原文纠错。
     */
    private Optional<byte[]> renderCleaned(long taskId, ArtifactFileKind kind, Path workDir) {
        if (kind != ArtifactFileKind.SEGMENTS && kind != ArtifactFileKind.SUBTITLES) {
            return Optional.empty();
        }
        Optional<SubtitleTrack> machineResult = trackReader.readCleanTrack(taskId, workDir);
        if (machineResult.isEmpty()) {
            return Optional.empty();
        }
        SubtitleTrack machine = machineResult.get();
        SubtitleTrack corrected = overlay.applyToCleanTrack(taskId, machine);
        if (corrected == machine) {
            return Optional.empty();
        }
        log.debug("下载正文叠加原文纠错: taskId={}, kind={}", taskId, kind.key());
        return Optional.of(kind == ArtifactFileKind.SEGMENTS
                ? serialize(corrected)
                : SrtRenderer.toSrt(corrected.segments()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 译文产物：只有 segments / subtitles 两个文件位要叠，而且只叠该语种的纠错。
     */
    private Optional<byte[]> renderTranslated(long taskId, String lang, ArtifactFileKind kind, Path workDir) {
        if (kind != ArtifactFileKind.SEGMENTS && kind != ArtifactFileKind.SUBTITLES) {
            return Optional.empty();
        }
        Optional<TranslatedTrack> machineResult = trackReader.readTranslatedTrack(taskId, lang, workDir);
        if (machineResult.isEmpty()) {
            return Optional.empty();
        }
        TranslatedTrack machine = machineResult.get();
        TranslatedTrack corrected = overlay.applyToTranslatedTrack(taskId, lang, machine);
        if (corrected == machine) {
            return Optional.empty();
        }
        log.debug("下载正文叠加译文纠错: taskId={}, lang={}, kind={}", taskId, lang, kind.key());
        return Optional.of(kind == ArtifactFileKind.SEGMENTS
                ? serialize(corrected)
                : SrtRenderer.toBilingualSrt(corrected.segments()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 与产物落盘同一套写法（同样的 mapper、同样的 pretty printer），避免下载到的 JSON 形状与磁盘漂移。
     */
    private byte[] serialize(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("叠加纠错后的字幕序列化失败：请检查字幕内容"
                    + "；原因：" + e.getMessage(), e);
        }
    }
}
