package com.vidsage.domain.intermediate;

import com.vidsage.domain.intermediate.model.IntermediateFileStatus;
import com.vidsage.domain.intermediate.model.IntermediateFileType;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 中间产物：task 级缓存里的一类文件（媒体源 / 元数据 / 音频），跨 run 存活。
 *
 * <p>{@code uk(task_id, file_name)} 决定了一个文件在一个 task 里只有一行，所以「重新产出」是
 * UPDATE 回 {@code READY} 复用同一行，不是插新行——这也是本表没有 {@code revision}、
 * 没有 {@code CREATING} 的原因。
 *
 * <p>本类<b>不建模状态迁移</b>：{@code READY} ⇄ {@code SUPERSEDED} → {@code DELETED} 这三条路径
 * 在生产里都是集合式 UPDATE（作废一次级联一整批、回收是删完文件再标），走仓储而不逐个实体。
 * 这里只负责出生（{@link #ofReady}）与还原（{@link #restore}）。
 */
@Getter
public class IntermediateFile {

    /** 数据库内部行 id；insert 回填，新建时在落库前为 {@code null}。 */
    private Long id;

    /** 所属字幕任务（逻辑外键 → subtitle_task.id）。 */
    private final long taskId;

    /** 工作区内文件名（文件系统上限即 255）。 */
    private final String fileName;

    /** 中间产物类型。 */
    private final IntermediateFileType fileType;

    /** 产物状态。变更走仓储的批量 UPDATE，本类只读它。 */
    private final IntermediateFileStatus status;

    /** 产出它的那条 run（审计，无承重用途），可空。 */
    private final Long derivedFromRunId;

    /** 创建时间。 */
    private final LocalDateTime createdAt;

    /** 最近变更时间。三条状态变更路径都在 SQL 里同句刷它，本类只读。 */
    private final LocalDateTime updatedAt;

    /** 私有构造：创建入口只有 {@link #ofReady} 与 {@link #restore}。 */
    private IntermediateFile(Long id, long taskId, String fileName, IntermediateFileType fileType,
                             IntermediateFileStatus status, Long derivedFromRunId,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.taskId = taskId;
        this.fileName = fileName;
        this.fileType = fileType;
        this.status = status;
        this.derivedFromRunId = derivedFromRunId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 首次产出一条中间产物：状态 READY（本表没有 CREATING，READY 是唯一入口；「先落盘、后登记」）。
     *
     * @param taskId           所属字幕任务
     * @param fileName         工作区内文件名
     * @param fileType         中间产物类型
     * @param derivedFromRunId 产出它的那条 run；无则传 {@code null}
     * @return 状态为 READY 的新中间产物（id 未落库，为 {@code null}）
     */
    public static IntermediateFile ofReady(long taskId, String fileName, IntermediateFileType fileType,
                                           Long derivedFromRunId) {
        LocalDateTime now = LocalDateTime.now();
        return new IntermediateFile(null, taskId, fileName, fileType, IntermediateFileStatus.READY,
                derivedFromRunId, now, now);
    }

    /**
     * 重建已落库的中间产物：仅供 persistence 层把数据库行还原成领域对象使用。接受任何状态，不做迁移校验。
     */
    public static IntermediateFile restore(Long id, long taskId, String fileName, IntermediateFileType fileType,
                                           IntermediateFileStatus status, Long derivedFromRunId,
                                           LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new IntermediateFile(id, taskId, fileName, fileType, status, derivedFromRunId, createdAt, updatedAt);
    }
}
