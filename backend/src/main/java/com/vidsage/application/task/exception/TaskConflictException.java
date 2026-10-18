package com.vidsage.application.task.exception;

/**
 * 冲突（409）：该任务已有一次执行在跑——插 RUNNING run 撞互斥键。
 *
 * <p>HTTP 层把它映射为 <b>409 Conflict</b>，响应体带 {@code {taskNo, runNo}}：冲突方拿这对
 * 去订阅那条正在跑的 run 的进度，不必再发一次查询。
 */
public class TaskConflictException extends RuntimeException {

    /** 已有 run 在跑的任务号。 */
    private final String taskNo;

    /** 那条正在跑的 run 的序号。 */
    private final int runNo;

    /**
     * 互斥撞键的冲突：文案说清「谁在跑、怎么看进度」。
     *
     * @param taskNo 已有 run 在跑的任务号
     * @param runNo  那条正在跑的 run 的序号
     */
    public TaskConflictException(String taskNo, int runNo) {
        this(taskNo, runNo, "该任务已有一次执行在跑：taskNo=" + taskNo + "，runNo=" + runNo
                + "；请等它跑完再触发，或订阅该 run 的 SSE 事件看进度");
    }

    /**
     * 带自定义文案的冲突。
     *
     * @param taskNo  已有 run 在跑的任务号
     * @param runNo   那条正在跑的 run 的序号
     * @param message 面向用户的错误信息（中文，缺什么 + 怎么补）
     */
    public TaskConflictException(String taskNo, int runNo, String message) {
        super(message);
        this.taskNo = taskNo;
        this.runNo = runNo;
    }

    /**
     * 已有 run 在跑的任务号，供 409 响应体携带。
     */
    public String getTaskNo() {
        return taskNo;
    }

    /**
     * 那条正在跑的 run 的序号，供 409 响应体携带。
     */
    public int getRunNo() {
        return runNo;
    }
}
