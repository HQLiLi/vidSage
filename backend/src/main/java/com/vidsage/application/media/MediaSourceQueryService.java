package com.vidsage.application.media;

import com.vidsage.application.media.exception.MediaSourceNotFoundException;
import com.vidsage.application.media.query.MediaSource;
import com.vidsage.application.task.SubtitleTaskFinder;
import com.vidsage.domain.intermediate.IntermediateFile;
import com.vidsage.domain.intermediate.model.IntermediateFileType;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.shared.fs.ArtifactFileUtils;
import com.vidsage.shared.media.MediaContentTypeUtils;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 找到任务的源媒体文件，并把「在哪、多大、什么格式」交给上层。
 *
 * <p>判据看表不看盘：{@code intermediate_file} 里 {@code READY} 的 {@code SOURCE_MEDIA} 行才是
 * 权威事实源，扫目录会把已作废的缓存当成有效缓存。表里登记了、文件却不在，只记 WARN、不改数据库行——
 * 文件可能只是被手工挪走，放回去就该重新可播（与 {@link IntermediateFileStore#isReusable} 同一口径）。
 */
@Slf4j
@Service
public class MediaSourceQueryService {

    private final SubtitleTaskFinder finder;
    private final IntermediateFileStore fileStore;

    public MediaSourceQueryService(SubtitleTaskFinder finder, IntermediateFileStore fileStore) {
        this.finder = finder;
        this.fileStore = fileStore;
    }

    /**
     * 定位该任务的源媒体。
     *
     * @param taskNo 对外任务号
     * @return 媒体文件的位置、文件名、MIME 与字节数
     * @throws IllegalArgumentException                                     taskNo 为空白（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     * @throws MediaSourceNotFoundException                                 没有 READY 的媒体行，或文件已不在 / 为空（404）
     * @throws IllegalStateException                                        同一任务出现多条 READY 的媒体行（数据自相矛盾）
     */
    public MediaSource locate(String taskNo) {
        SubtitleTask task = finder.require(taskNo);
        Workspace workspace = finder.requireWorkspace(task);
        Path workDir = finder.workspaceDir(workspace);

        List<IntermediateFile> sources = fileStore.findReady(task.getId()).stream()
                .filter(file -> file.getFileType() == IntermediateFileType.SOURCE_MEDIA)
                .toList();
        if (sources.isEmpty()) {
            throw new MediaSourceNotFoundException(taskNo);
        }
        if (sources.size() > 1) {
            throw new IllegalStateException("同一任务出现多条 READY 的 SOURCE_MEDIA：taskNo=" + taskNo
                    + "，条数=" + sources.size() + "；数据自相矛盾，请对该任务重新生成后再试");
        }

        IntermediateFile source = sources.getFirst();
        Path path = workDir.resolve(source.getFileName());
        long size = ArtifactFileUtils.sizeOrZero(path);
        if (size == 0) {
            // 与复用判据一致：只记 WARN、不改数据库行，文件放回去就该重新可播
            log.warn("媒体源登记为 READY 但文件不在或为空，按不可用处理: taskId={}, file={}",
                    task.getId(), source.getFileName());
            throw new MediaSourceNotFoundException(taskNo);
        }
        return new MediaSource(path, source.getFileName(), MediaContentTypeUtils.detect(path), size);
    }
}
