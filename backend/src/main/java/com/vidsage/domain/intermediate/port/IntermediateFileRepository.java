package com.vidsage.domain.intermediate.port;

import com.vidsage.domain.intermediate.IntermediateFile;
import com.vidsage.domain.intermediate.model.IntermediateFileType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 中间产物持久化的出口：领域侧只说「能做什么」，底下是 MySQL 还是别的存储，领域层不关心。
 */
public interface IntermediateFileRepository {

    /**
     * 首次产出一条中间产物（{@code READY} 是唯一入口，本表没有 {@code CREATING}）。
     *
     * @param file 待存的中间产物（经 {@code IntermediateFile.ofReady} 创建）
     * @return 回填了数据库 id 的中间产物
     */
    IntermediateFile insertReady(IntermediateFile file);

    /**
     * 取该 task 的全部中间产物（级联要遍历）。
     *
     * @param taskId 所属字幕任务
     * @return 全部中间产物（各种状态都含）；没有时是空列表
     */
    List<IntermediateFile> findByTaskId(long taskId);

    /**
     * 复用判据：「表里 {@code READY} <b>且</b>文件在」——本方法只答前半句，文件在不在由调用方查盘。
     *
     * @param taskId   所属字幕任务
     * @param fileName 工作区内文件名
     * @return {@code READY} 的那一行；没有时为空
     */
    Optional<IntermediateFile> findReadyByTaskIdAndFileName(long taskId, String fileName);

    /**
     * 按文件名取该 task 的中间产物，<b>不限状态</b>。重新产出时用它决定「插新行还是复活旧行」：
     * {@code uk(task_id, file_name)} 决定一个文件只有一行。
     *
     * @param taskId   所属字幕任务
     * @param fileName 工作区内文件名
     * @return 那一行（任意状态）；没有时为空
     */
    Optional<IntermediateFile> findByTaskIdAndFileName(long taskId, String fileName);

    /**
     * 重新产出：{@code SUPERSEDED} / {@code DELETED} → {@code READY}，复用同一行
     * （{@code uk(task_id, file_name)} 唯一），并同句刷 {@code file_type}、
     * {@code derived_from_run_id} 与 {@code updated_at}。
     *
     * <p>审计列一起更新：重产出之后「产出它的那条 run」就是本次 run，留着旧值会让这列撒谎。
     *
     * @param id                中间产物行 id
     * @param fileType          中间产物类型（文件名决定类型，复活时一并纠正）
     * @param derivedFromRunId  本次产出它的 run；无则传 {@code null}
     * @param at                本次变更时刻（由调用方传入，以便与同一次收尾的其它写入对齐）
     * @return 受影响行数
     */
    int markReady(long id, IntermediateFileType fileType, Long derivedFromRunId, LocalDateTime at);

    /**
     * 内容变更级联：指纹变了 → 该 task 的中间产物全标 {@code SUPERSEDED}
     * （只动当前 {@code READY} 的，已作废 / 已回收的不回退）。一条按 {@code task_id} 过滤的 UPDATE，
     * 不在仓储里写循环。
     *
     * @param taskId 所属字幕任务
     * @param at     本次变更时刻（由调用方传入，一个事务里要跟 artifact 级联、workspace 指纹对齐同一时刻）
     * @return 受影响行数
     */
    int markSupersededByTaskId(long taskId, LocalDateTime at);

    /**
     * 标已回收（两阶段：本表没有文件列可清空，所以必须「删文件 → 标 {@code DELETED}」）。
     *
     * @param id 中间产物行 id
     * @param at 本次变更时刻（由调用方传入，以便与同一次收尾的其它写入对齐）
     * @return 受影响行数
     */
    int markDeleted(long id, LocalDateTime at);
}
