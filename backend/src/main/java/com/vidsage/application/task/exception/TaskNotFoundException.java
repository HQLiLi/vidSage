package com.vidsage.application.task.exception;

/**
 * taskNo 对应的任务不存在：查询 / SSE 订阅 / 产物下载三个按 taskNo 取任务的端点统一抛出，
 * HTTP 层把它映射为 <b>404 Not Found</b>。判据与引入条件见 error-handling.md「尚未建立的约定」。
 *
 * <p>与查重 409 的分工：409 =「任务存在但不能重复提交」；404 =「任务根本不存在」。
 */
public class TaskNotFoundException extends RuntimeException {

    /**
     * @param taskNo 未命中任何任务的对外任务号（进错误消息，帮助调用方核对）
     */
    public TaskNotFoundException(String taskNo) {
        super("任务不存在：" + taskNo + "；请核对 taskNo（任务号在提交响应的 taskNo 字段中），"
                + "或确认任务是否已被清理");
    }
}
