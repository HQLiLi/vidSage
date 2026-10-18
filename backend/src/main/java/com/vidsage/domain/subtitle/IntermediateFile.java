package com.vidsage.domain.subtitle;

import com.vidsage.domain.subtitle.model.IntermediateFileStatus;
import com.vidsage.domain.subtitle.model.IntermediateFileType;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 中间产物：task 级缓存里的一类文件（媒体源 / 元数据 / 音频），跨 run 存活。
 *
 * <p>{@code uk(task_id, file_name)} 决定了一个文件在一个 task 里只有一行，所以「重新产出」是
 * {@link #revive} 复用同一行，不是插新行——这也是本表没有 {@code revision}、没有 {@code CREATING} 的原因。
 *
 * <p>状态机只有三个值：{@code READY}（工厂出生 / {@link #revive} 复活）⇄
 * {@code SUPERSEDED}（{@link #supersede}）→ {@code DELETED}（{@link #confirmDeletion}）。
 * {@code CREATING} / {@code FAILED} 对纯缓存是硬套，故没有。
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

    /** 产物状态。 */
    private IntermediateFileStatus status;

    /** 产出它的那条 run（审计，无承重用途），可空。 */
    private final Long derivedFromRunId;

    /** 创建时间。 */
    private final LocalDateTime createdAt;

    /** 最近变更时间。 */
    private LocalDateTime updatedAt;

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

    /**
     * 重新产出：{@code SUPERSEDED} / {@code DELETED} → {@code READY}，复用同一行
     * （{@code uk(task_id, file_name)} 唯一，所以是 UPDATE 回 READY 而不是插新行）。
     *
     * @throws IllegalStateException 当前不是 SUPERSEDED / DELETED
     */
    public void revive() {
        if (status != IntermediateFileStatus.SUPERSEDED && status != IntermediateFileStatus.DELETED) {
            throw new IllegalStateException("非法状态迁移：" + status + " → READY（只有 SUPERSEDED / DELETED "
                    + "的中间产物可以重新产出回 READY），当前状态为 " + status);
        }
        status = IntermediateFileStatus.READY;
        updatedAt = LocalDateTime.now();
    }

    /**
     * READY → SUPERSEDED：内容变更级联（指纹变了）——已作废，文件待删。
     *
     * @throws IllegalStateException 当前不是 READY
     */
    public void supersede() {
        validateStatus(IntermediateFileStatus.READY, "SUPERSEDED（只有 READY 的中间产物会被作废）");
        status = IntermediateFileStatus.SUPERSEDED;
        updatedAt = LocalDateTime.now();
    }

    /**
     * SUPERSEDED → DELETED：文件已确认回收之后标（两阶段——本表没有文件列可清空，
     * 所以必须「删文件 → 标 DELETED」）。
     *
     * @throws IllegalStateException 当前不是 SUPERSEDED
     */
    public void confirmDeletion() {
        validateStatus(IntermediateFileStatus.SUPERSEDED, "DELETED（只有 SUPERSEDED 的中间产物可以标记已回收）");
        status = IntermediateFileStatus.DELETED;
        updatedAt = LocalDateTime.now();
    }

    /** 状态守卫：当前状态与迁移目标不符即拒绝，消息给出「当前状态 → 目标状态」与合法前提。 */
    private void validateStatus(IntermediateFileStatus required, String targetDescription) {
        if (status != required) {
            throw new IllegalStateException("非法状态迁移：" + status + " → " + targetDescription
                    + "，当前状态为 " + status);
        }
    }
}
