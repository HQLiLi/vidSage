package com.vidsage.web.dto;

import com.vidsage.application.task.TaskRunRef;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 触发类端点的返回载荷：本次触发的是哪个任务的哪一次执行。
 *
 * <p>提交、上传、重新生成、翻译四个触发点统一返回这一对；202 与 409 同形状，
 * 调用方拿它去订阅那条 run 的进度。409 不含文案，靠状态码区分。
 *
 * @param taskNo 对外任务号
 * @param runNo  本次执行的序号
 */
@Schema(description = "触发结果：本次是哪个任务的哪一次执行（202 与 409 同形状）")
public record TaskRunRefResponse(
        @Schema(description = "对外任务号", example = "3f2a9c1e7b5d4a6089abcdef01234567") String taskNo,
        @Schema(description = "本次执行的序号，从 1 起", example = "1") int runNo) {

    /**
     * 应用层读模型 → 对外响应的纯映射（无业务逻辑）：字段一一对应。
     *
     * @param ref 应用层返回的触发结果
     * @return 对外响应
     */
    public static TaskRunRefResponse from(TaskRunRef ref) {
        return new TaskRunRefResponse(ref.taskNo(), ref.runNo());
    }
}
