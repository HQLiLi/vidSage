package com.vidsage.model.task;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 任务输入类型：提交任务的两种来源路线。本表与数据库 {@code task.input_type} 的 ENUM 值一一对应。
 */
@Schema(description = "输入类型：URL（链接提交）/ UPLOAD（本站文件上传）")
public enum TaskInputType {

    /** 远程链接（B 站 / YouTube 等），由下载器拉取媒体。 */
    URL,

    /** 用户上传的本地视频 / 音频文件，已流式落盘到任务工作区（source.mp4）。 */
    UPLOAD
}
