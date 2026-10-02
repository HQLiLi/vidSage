package com.vidsage.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * URL 任务提交请求体（POST /api/tasks）。
 *
 * @param url 公网媒体页面地址（B 站 / YouTube 等）。文件上传不要走本端点（请用
 *            POST /api/tasks/upload）——本项目没有「一个请求里 URL 与文件混传」的形态
 */
public record SubmitUrlRequest(
        @Schema(description = "公网媒体页面地址", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "https://www.bilibili.com/video/BVxxxxxxxx") String url) {
}
