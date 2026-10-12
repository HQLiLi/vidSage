package com.vidsage.application.task.exception;

import com.vidsage.application.task.query.TaskSnapshot;

/**
 * 冲突（两个场景共用 409）：① 同一输入已有进行中（PENDING / RUNNING）的任务——提交查重命中；
 * ② 同一工作区已有后置会话（重新生成字幕）在跑——互斥抢登记失败。两个场景的错误文案不同，
 * ②走带 message 的构造器。HTTP 层把它映射为 <b>409 Conflict</b>，响应体附既有任务快照
 * （应用侧载荷是零 web 注解的读模型 {@link TaskSnapshot}）。
 *
 * <p>为什么携带既有任务快照：冲突的可操作出路是「改用既有任务、去订阅它的进度」，
 * 光有文字说明不够，把快照一并返回，调用方无需再发一次查询。
 *
 * <p>两个并发提交同输入都通过查重的窗口（先查后插的竞态）刻意接受：窗口毫秒级，
 * 且断点复用让「重跑」很便宜，为此上数据库唯一索引反而把「任务完成后重新提交」
 * 这条合法路径也堵死了。
 */
public class TaskConflictException extends RuntimeException {

    /** 已存在的进行中任务快照（应用层读模型）。 */
    private final TaskSnapshot existingTask;

    /**
     * 查重命中的冲突：文案沿用构造器默认那套「重复提交」口径。
     *
     * @param existingTask 查重命中的既有任务（PENDING / RUNNING）
     */
    public TaskConflictException(TaskSnapshot existingTask) {
        this(existingTask, "该输入已有进行中的任务：taskNo=" + existingTask.taskNo()
                + "（status=" + existingTask.status() + "）；请勿重复提交，"
                + "可用响应体 existingTask 里的 taskNo 查询或订阅既有任务的进度");
    }

    /**
     * 冲突的另一种场景：同工作区已有<b>后置会话</b>（重新生成字幕）在跑。
     * 它与查重共享 409，但默认文案会说成「该输入已有进行中的任务…请勿重复提交」——
     * 冲突方手里<b>就是那个任务号</b>（status 还是 SUCCEEDED），照默认文案读会以为提交写错了。
     * 故文案由调用方给，说清「谁在跑、等什么、怎么看进度」。
     *
     * @param existingTask 既有任务快照（此处通常就是触发方自己，附给前端订阅用）
     * @param message      该场景专属的错误信息（中文，缺什么 + 怎么补）
     */
    public TaskConflictException(TaskSnapshot existingTask, String message) {
        super(message);
        this.existingTask = existingTask;
    }

    /**
     * 已存在的进行中任务快照，供 409 响应体携带。
     */
    public TaskSnapshot getExistingTask() {
        return existingTask;
    }
}
