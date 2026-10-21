package com.vidsage.application.task;

/**
 * 触发类端点的返回载荷：本次触发的是哪个任务的哪一次执行。
 *
 * <p>提交、上传、重新生成、翻译四个触发点统一返回这一对；与 409 冲突响应同形状，
 * 调用方拿它去订阅那条 run 的进度。
 *
 * @param taskNo 对外任务号
 * @param runNo  本次执行的序号
 */
public record TaskRunRef(String taskNo, int runNo) {
}
