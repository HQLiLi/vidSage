package com.vidsage.application.subtitle;

import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactFileKind;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.subtitle.model.translation.TranslatedTrack;
import com.vidsage.shared.fs.ArtifactFileUtils;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 按任务读当前完好的 CLEANED / TRANSLATED 字幕（segments JSON）。
 *
 * <p>读的是<b>磁盘上的机器产物</b>，纠错覆盖层还没叠上去；要「用户看到的样子」请再交给
 * {@link SubtitleCorrectionOverlay}。纠错校验、下载、翻译取源文本都从这里取同一份内容，
 * 免得各处再写一遍「找产物 → 求文件名 → 反序列化」。
 *
 * <p>读不出来一律当「没有」：产物行不完整、文件不见了、JSON 坏了都返回空并记日志，
 * <b>不抛 500</b>——读取路径的调用方要用「有没有」来决定怎么提示用户，而不是被异常打断。
 */
@Slf4j
@Component
public class SubtitleTrackReader {

    private final SubtitleArtifactStore artifactStore;

    private final ObjectMapper objectMapper;

    /**
     * @param artifactStore 产物门面（判断「完好的当前版」并给出文件名）
     * @param objectMapper  Jackson 3 反序列化器（Boot 4 只有 tools.jackson 这份有 bean）
     */
    public SubtitleTrackReader(SubtitleArtifactStore artifactStore, ObjectMapper objectMapper) {
        this.artifactStore = artifactStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 读该任务当前完好的清洗字幕。
     *
     * @param taskId  字幕任务的内部行 id
     * @param workDir 工作区目录
     * @return 清洗后的字幕；没有完好的 CLEANED 产物或读取失败时为空
     */
    public Optional<SubtitleTrack> readCleanTrack(long taskId, Path workDir) {
        return readTrack(taskId, ArtifactType.CLEANED, "", SubtitleTrack.class, workDir);
    }

    /**
     * 读该任务某个语种当前完好的译文。
     *
     * @param taskId  字幕任务的内部行 id
     * @param lang    目标语种（<b>已归一的短码</b>，如 {@code en} / {@code zh-Hans}；本方法不再解析别名）
     * @param workDir 工作区目录
     * @return 译文；没有该语种完好的 TRANSLATED 产物或读取失败时为空
     */
    public Optional<TranslatedTrack> readTranslatedTrack(long taskId, String lang, Path workDir) {
        return readTrack(taskId, ArtifactType.TRANSLATED, lang, TranslatedTrack.class, workDir);
    }

    /**
     * 两件事收在一处：先要「完好的当前版」，再读它登记的 segments 文件。
     *
     * <p>日志只带固定文件名与 taskId，不带目录路径——目录名内嵌媒体身份键，属脱敏红线。
     */
    private <T> Optional<T> readTrack(long taskId, ArtifactType artifactType, String lang,
                                      Class<T> targetType, Path workDir) {
        Optional<SubtitleArtifact> artifact =
                artifactStore.findIntactArtifact(taskId, artifactType, lang, workDir);
        if (artifact.isEmpty()) {
            // 还没产出 / 这一版不完整：正常形态，交给调用方按「没有字幕」处理
            log.debug("没有完好的产物，按没有字幕处理: taskId={}, type={}, lang={}", taskId, artifactType, lang);
            return Optional.empty();
        }
        String fileName = artifact.get().getFileName(ArtifactFileKind.SEGMENTS);
        if (fileName == null) {
            // 完好判定本应挡住：缺必需文件位说明产物表与文件对不上
            log.warn("产物缺少 segments 文件位登记，按没有处理: taskId={}, type={}, lang={}",
                    taskId, artifactType, lang);
            return Optional.empty();
        }
        Path file = workDir.resolve(fileName);
        if (!ArtifactFileUtils.isNonEmpty(file)) {
            log.warn("产物登记的 segments 文件不在或为空，按没有处理: taskId={}, type={}, lang={}, file={}",
                    taskId, artifactType, lang, fileName);
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), targetType));
        } catch (JacksonException e) {
            log.warn("字幕 segments 解析失败，按没有处理: taskId={}, type={}, lang={}, file={}, reason={}",
                    taskId, artifactType, lang, fileName, e.getMessage());
            return Optional.empty();
        }
    }
}
