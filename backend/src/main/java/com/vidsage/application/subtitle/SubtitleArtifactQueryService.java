package com.vidsage.application.subtitle;

import com.vidsage.application.subtitle.exception.ArtifactNotFoundException;
import com.vidsage.application.subtitle.query.ArtifactDownload;
import com.vidsage.application.subtitle.query.ArtifactFileSnapshot;
import com.vidsage.application.subtitle.query.ArtifactSnapshot;
import com.vidsage.application.task.SubtitleTaskFinder;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactFileKind;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.workspace.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 产物清单与下载的读路径：只给当前有效版，文件不在就按没有处理。
 *
 * <p>清单每次都现查数据库、现 stat 文件，不做缓存——单实例本地盘，量级不构成问题。
 * 清单与文件大小的读取之间有窗口：文件刚好被删掉时把整版从本次结果里去掉，不抛 500。
 *
 * <p>下载的参数在入口判（未知类型 / 未知文件 / 文件位与类型不匹配 / 语种缺失或多余 → 400），
 * 产物不可用时抛 {@link ArtifactNotFoundException}（404）。
 */
@Slf4j
@Service
public class SubtitleArtifactQueryService {

    private final SubtitleTaskFinder finder;
    private final SubtitleArtifactStore artifactStore;

    public SubtitleArtifactQueryService(SubtitleTaskFinder finder, SubtitleArtifactStore artifactStore) {
        this.finder = finder;
        this.artifactStore = artifactStore;
    }

    /**
     * 列该任务当前可读的全部产物版本。
     *
     * @param taskNo 对外任务号
     * @return 按类型（原始 → 清洗 → 翻译）再按语种排序的清单；文件不齐的版本不出现
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在
     */
    public List<ArtifactSnapshot> listArtifacts(String taskNo) {
        SubtitleTask task = finder.require(taskNo);
        Workspace workspace = finder.requireWorkspace(task);
        Path workDir = finder.workspaceDir(workspace);
        return artifactStore.listReadableReady(task.getId(), workDir).stream()
                .sorted(Comparator.comparingInt((SubtitleArtifact artifact) -> artifact.getArtifactType().ordinal())
                        .thenComparing(SubtitleArtifact::getLang))
                .map(artifact -> snapshotOf(artifact, workDir))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * 解析一次下载：参数合法性在入口判，产物可用性交给可读判定。
     *
     * @param taskNo    对外任务号
     * @param typeToken 产物类型（不区分大小写）
     * @param fileToken 文件名段，如 {@code subtitles.srt}
     * @param langToken 目标语种；只有翻译产物要带
     * @return 要读的文件路径与文件位
     * @throws IllegalArgumentException 类型 / 文件名段 / 语种参数不合法
     * @throws ArtifactNotFoundException 该版产物不可用（含文件已被挪走）
     */
    public ArtifactDownload openArtifact(String taskNo, String typeToken, String fileToken, String langToken) {
        ArtifactType type = parseType(typeToken);
        ArtifactFileKind kind = parseFileKind(fileToken);
        if (!kind.belongsTo(type)) {
            throw new IllegalArgumentException("产物类型 " + type + " 不带 " + fileToken
                    + "：请按 GET /api/tasks/{taskNo}/artifacts 返回的 files 列表下载");
        }
        String lang = resolveLang(type, langToken);

        SubtitleTask task = finder.require(taskNo);
        Workspace workspace = finder.requireWorkspace(task);
        Path workDir = finder.workspaceDir(workspace);
        SubtitleArtifact artifact = artifactStore.findReadableReady(task.getId(), type, lang, workDir)
                .orElseThrow(() -> new ArtifactNotFoundException(taskNo, type.name(), fileToken));
        String fileName = artifact.getFileName(kind);
        if (fileName == null) {
            // 可读判定本应已挡住，这里只是防御：文件列空 = 这版其实不可用
            throw new ArtifactNotFoundException(taskNo, type.name(), fileToken);
        }
        return new ArtifactDownload(workDir.resolve(fileName), kind);
    }

    /**
     * 把一版产物组装成读模型；读文件大小时文件刚好没了就把整版去掉。
     */
    private Optional<ArtifactSnapshot> snapshotOf(SubtitleArtifact artifact, Path workDir) {
        List<ArtifactFileSnapshot> files = new ArrayList<>();
        for (ArtifactFileKind kind : ArtifactFileKind.values()) {
            if (!kind.belongsTo(artifact.getArtifactType())) {
                continue;
            }
            String fileName = artifact.getFileName(kind);
            if (fileName == null) {
                if (kind.isRequiredFor(artifact.getArtifactType())) {
                    return Optional.empty();
                }
                // 可选位没有文件：清单里少一项，整版仍然可用
                continue;
            }
            try {
                files.add(new ArtifactFileSnapshot(kind, Files.size(workDir.resolve(fileName))));
            } catch (IOException e) {
                log.warn("列举产物时文件已不可读，本次不列出该版本: taskId={}, type={}, lang={}, file={}",
                        artifact.getTaskId(), artifact.getArtifactType(), artifact.getLang(), kind.key());
                return Optional.empty();
            }
        }
        return Optional.of(new ArtifactSnapshot(artifact.getArtifactType(), artifact.getLang(),
                artifact.getRevision(), artifact.getKeywordsFingerprint(), List.copyOf(files)));
    }

    /**
     * 解析产物类型：大小写不敏感，未知值当场拒绝。
     */
    private static ArtifactType parseType(String token) {
        if (!StringUtils.hasText(token)) {
            throw new IllegalArgumentException("产物类型不能为空：可选值 ORIGINAL / CLEANED / TRANSLATED");
        }
        String normalized = token.trim().toUpperCase(Locale.ROOT);
        for (ArtifactType type : ArtifactType.values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的产物类型：" + token + "；可选值 ORIGINAL / CLEANED / TRANSLATED");
    }

    /**
     * 解析文件名段：只认四个固定段。
     */
    private static ArtifactFileKind parseFileKind(String token) {
        return ArtifactFileKind.fromToken(token)
                .orElseThrow(() -> new IllegalArgumentException("未知的产物文件：" + token
                        + "；可选值 segments.json / subtitles.srt / context.json / terms.json"));
    }

    /**
     * 归一目标语种：翻译产物必填（短码与其它写法等价），其余类型不许带。
     */
    private static String resolveLang(ArtifactType type, String langToken) {
        if (type == ArtifactType.TRANSLATED) {
            if (!StringUtils.hasText(langToken)) {
                throw new IllegalArgumentException(
                        "TRANSLATED 产物必须带 lang：请加 ?lang=zh-Hans 之类的目标语种参数");
            }
            return SupportedLanguage.parse(langToken).getCode();
        }
        if (StringUtils.hasText(langToken)) {
            throw new IllegalArgumentException(type + " 产物没有语言维度，不能带 lang 参数：请去掉 ?lang=");
        }
        return "";
    }
}
