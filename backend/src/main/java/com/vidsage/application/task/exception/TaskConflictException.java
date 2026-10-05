package com.vidsage.application.task.exception;

import com.vidsage.application.task.query.TaskView;

/**
 * 同一输入已有进行中（PENDING / RUNNING）的任务：提交查重命中。HTTP 层把它映射为
 * <b>409 Conflict</b>（见 {@code ApiExceptionHandler}），响应体附既有任务视图。
 *
 * <p>为什么此刻引入第一个自定义异常：error-handling.md 约定「不提前造异常体系，等需要跨层区分
 * 错误码时再引入」——冲突需要 409，而既有 IAE / ISE 映射只覆盖 400 / 500，触发条件首次成立。
 * 预期仅此一个：其他错误仍按既有 IAE / ISE 语义表达。
 *
 * <p>为什么携带既有任务视图：冲突的可操作出路是「改用既有任务、去订阅它的进度」，
 * 光有文字说明不够，把视图一并返回，调用方无需再发一次查询。
 *
 * <p>两个并发提交同输入都通过查重的窗口（先查后插的竞态）刻意接受：窗口毫秒级，
 * 且断点复用让「重跑」很便宜，为此上数据库唯一索引反而把「任务完成后重新提交」
 * 这条合法路径也堵死了（design.md 权衡记录）。
 */
public class TaskConflictException extends RuntimeException {

    /** 已存在的进行中任务视图。 */
    private final TaskView existingTask;

    /**
     * @param existingTask 查重命中的既有任务（PENDING / RUNNING）
     */
    public TaskConflictException(TaskView existingTask) {
        super("该输入已有进行中的任务：taskNo=" + existingTask.taskNo()
                + "（status=" + existingTask.status() + "）；请勿重复提交，"
                + "可用响应体 existingTask 里的 taskNo 查询或订阅既有任务的进度");
        this.existingTask = existingTask;
    }

    /**
     * 已存在的进行中任务视图，供 409 响应体携带。
     */
    public TaskView getExistingTask() {
        return existingTask;
    }
}
