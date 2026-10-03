package com.vidsage.service;

/**
 * taskNo 对应的任务不存在：查询 / SSE 订阅 / 产物下载三个按 taskNo 取任务的端点统一抛出，
 * HTTP 层把它映射为 <b>404 Not Found</b>（见 {@code ApiExceptionHandler}）。
 *
 * <p>为什么此刻引入第二个自定义异常：error-handling.md 约定「不提前造异常体系，等需要跨层区分
 * 错误码时再引入」。本异常引入前，「任务不存在」走 {@code IllegalArgumentException} → 400——
 * 但「请求格式没错，只是这个 taskNo 不存在」语义上是 404 而非 400：SSE 订阅与产物下载两个新端点
 * 落地后，「按 taskNo 找不到资源」首次成为需要与「参数不合法」区分的独立错误（前端据此决定
 * 提示「任务不存在」还是回退提交页）。同 {@link TaskConflictException} 的引入判据：状态码需要
 * 跨层区分时才造类型。预期仅此一个新增；其他错误仍按既有 IAE / ISE 语义表达。
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
