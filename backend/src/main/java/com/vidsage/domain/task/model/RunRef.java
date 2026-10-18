package com.vidsage.domain.task.model;

import com.vidsage.domain.task.TaskRun;

/**
 * 一条 run 的对外引用：{@code (taskNo, runNo)} 这一对。
 *
 * <p>订阅与连接共用这一个键——同一个 task 跑两次翻译是两个不同的地址，不歧义。
 *
 * @param taskNo 所属任务的对外号
 * @param runNo  任务内递增序号，从 1 起
 */
public record RunRef(TaskNo taskNo, int runNo) {

    /**
     * 校验硬不变量：runNo 从 1 起。非正数说明上游没落库就拿来用了（服务端缺陷）。
     *
     * @throws IllegalStateException runNo 小于 1
     */
    public RunRef {
        if (runNo < 1) {
            throw new IllegalStateException("runNo 必须从 1 起：runNo 由 task_run.run_no 生成（MAX+1），当前 " + runNo);
        }
    }

    /**
     * 由调用方手里的任务号与 run 拼出对外引用。
     *
     * @param taskNo 所属任务的对外号（调用方从 {@code SubtitleTask} 取）
     * @param run    已落库的 run（带 runNo）
     * @return 该 run 的对外引用
     */
    public static RunRef of(TaskNo taskNo, TaskRun run) {
        return new RunRef(taskNo, run.getRunNo());
    }
}
