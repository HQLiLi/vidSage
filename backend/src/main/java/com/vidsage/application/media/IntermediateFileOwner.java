package com.vidsage.application.media;

/**
 * 一次执行在中间产物表里的归属：这些文件属于哪个字幕任务、由哪条 run 产出。
 *
 * <p>媒体 / 音频准备阶段本身不认识任务与 run，但中间产物要按 {@code (task_id, file_name)}
 * 登记、审计列要记产出它的 run，所以由管线把这份归属传下来。
 *
 * @param taskId 所属字幕任务
 * @param runId  产出这些文件的那条 run
 */
public record IntermediateFileOwner(long taskId, long runId) {
}
