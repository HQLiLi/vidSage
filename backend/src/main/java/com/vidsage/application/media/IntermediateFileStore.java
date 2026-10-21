package com.vidsage.application.media;

import com.vidsage.domain.intermediate.IntermediateFile;
import com.vidsage.domain.intermediate.model.IntermediateFileStatus;
import com.vidsage.domain.intermediate.model.IntermediateFileType;
import com.vidsage.domain.intermediate.port.IntermediateFileRepository;
import com.vidsage.shared.fs.ArtifactFileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 中间产物（媒体源 / 元信息 / 音频）的登记与复用判据。
 *
 * <p><b>复用判据是「表里 READY 且文件在」，不是「文件在不在」</b>：内容变更级联只动数据库、
 * 不会（也不该）去删干净每一份文件，光看盘会把已经作废的缓存当成有效缓存。这条判据是
 * 「指纹变了等于没变」那个静默错交付的堵口之一。
 *
 * <p><b>先落盘、后登记</b>：{@link #recordProduced} 只在文件已经原子落盘之后调用，所以
 * 「表里 READY」蕴含「文件完整」。
 */
@Slf4j
@Component
public class IntermediateFileStore {

    private final IntermediateFileRepository repository;

    public IntermediateFileStore(IntermediateFileRepository repository) {
        this.repository = repository;
    }

    /**
     * 复用判据：该文件在这个 task 里登记为 {@code READY}，且工作区里确实有这个普通文件。
     *
     * <p>登记为 READY 但文件不在时记一条 WARN、按不可复用处理，<b>不改状态</b>——文件可能只是
     * 被手工挪走，放回去就该重新可见（与产物读路径的自愈口径一致）。
     *
     * @param taskId   所属字幕任务
     * @param fileName 工作区内文件名
     * @param workDir  工作区目录
     * @return 可复用时返回 {@code true}
     */
    public boolean isReusable(long taskId, String fileName, Path workDir) {
        if (repository.findReadyByTaskIdAndFileName(taskId, fileName).isEmpty()) {
            return false;
        }
        if (!ArtifactFileUtils.isRegularFile(workDir.resolve(fileName))) {
            log.warn("中间产物登记为 READY 但文件不在，按不可复用处理: taskId={}, file={}", taskId, fileName);
            return false;
        }
        return true;
    }

    /**
     * 真正产出一个文件之后登记它：没有行就插一条 {@code READY}，已有行就复活并刷新类型、
     * 产出 run 与变更时刻。
     *
     * <p>只登记工作区内的文件：本地路线可能直接采用工作区外的文件，那种文件不属 task 级缓存，
     * 不该进表、也不该参与复用判据。
     *
     * @param taskId       所属字幕任务
     * @param runId        产出它的 run
     * @param type         中间产物类型
     * @param workDir      工作区目录
     * @param producedFile 刚落盘的文件路径
     */
    public void recordProduced(long taskId, long runId, IntermediateFileType type, Path workDir, Path producedFile) {
        if (!isInsideWorkspace(workDir, producedFile)) {
            log.debug("跳过工作区外文件的中间产物登记: taskId={}, file={}", taskId, producedFile.getFileName());
            return;
        }
        String fileName = producedFile.getFileName().toString();
        Optional<IntermediateFile> existing = repository.findByTaskIdAndFileName(taskId, fileName);
        if (existing.isEmpty()) {
            repository.insertReady(IntermediateFile.ofReady(taskId, fileName, type, runId));
            return;
        }
        // 已有一行（作废过或文件丢失后重产出）：复活它，不插新行——一个文件在一个 task 里只有一行
        repository.markReady(existing.get().getId(), type, runId, LocalDateTime.now());
    }

    /**
     * 取该 task 当前 {@code READY} 的中间产物（级联前先在事务里读出来，供事务外回收）。
     *
     * @param taskId 所属字幕任务
     * @return {@code READY} 的行；没有时是空列表
     */
    public List<IntermediateFile> findReady(long taskId) {
        return repository.findByTaskId(taskId).stream()
                .filter(file -> file.getStatus() == IntermediateFileStatus.READY)
                .toList();
    }

    /**
     * 内容变更级联：该 task 当前 {@code READY} 的中间产物全标 {@code SUPERSEDED}。
     *
     * @param taskId 所属字幕任务
     * @param at     本次变更时刻（与同一事务里的产物级联、指纹写入对齐）
     * @return 受影响行数
     */
    public int supersedeAll(long taskId, LocalDateTime at) {
        return repository.markSupersededByTaskId(taskId, at);
    }

    /**
     * 两阶段回收：删掉文件之后才标 {@code DELETED}。
     *
     * <p>删不动（文件被占用、权限不足）只记 WARN，那一行停在 {@code SUPERSEDED} 留给对账——
     * 本次执行不该因为回收没做完而判失败。
     *
     * @param file    已作废的中间产物行
     * @param workDir 工作区目录
     */
    public void reclaimSuperseded(IntermediateFile file, Path workDir) {
        try {
            Files.deleteIfExists(workDir.resolve(file.getFileName()));
            repository.markDeleted(file.getId(), LocalDateTime.now());
        } catch (IOException | RuntimeException e) {
            // 只记文件名与异常类名：异常消息可能带完整路径，而目录名内嵌身份键（脱敏红线）
            log.warn("中间产物回收失败，留在待回收状态: taskId={}, file={}, reason={}",
                    file.getTaskId(), file.getFileName(), e.getClass().getSimpleName());
        }
    }

    /**
     * 文件是不是在工作区目录之内（用绝对规范路径比，避免相对路径 / {@code ..} 造成误判）。
     */
    private static boolean isInsideWorkspace(Path workDir, Path file) {
        return file.toAbsolutePath().normalize().startsWith(workDir.toAbsolutePath().normalize());
    }
}
