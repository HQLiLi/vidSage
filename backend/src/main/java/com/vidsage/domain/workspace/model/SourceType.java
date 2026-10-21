package com.vidsage.domain.workspace.model;

/**
 * 媒体来源类型：提交进来的两种路线。取值与数据库 {@code workspace.source_type} 列一一对应。
 */
public enum SourceType {

    /** 远程链接（B 站 / YouTube 等），由下载器拉取媒体。 */
    URL,

    /** 用户上传的本地视频 / 音频文件，已流式落盘到任务工作区（source.&lt;ext&gt;）。 */
    UPLOAD
}
