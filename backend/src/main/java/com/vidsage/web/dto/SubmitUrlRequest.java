package com.vidsage.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * URL 任务提交请求体（POST /api/tasks）。
 *
 * @param url      公网媒体页面地址（B 站 / YouTube 等）。文件上传不要走本端点（请用
 *                 POST /api/tasks/upload）——本项目没有「一个请求里 URL 与文件混传」的形态
 * @param keywords 可选的关键词清单（专有名词正确写法，供 LLM 纠 ASR 同音误写）；
 *                 缺席 = 不写关键词（不是清空——提交时还没有可清的东西）
 */
public record SubmitUrlRequest(
        @Schema(description = "公网媒体页面地址", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "https://www.bilibili.com/video/BVxxxxxxxx") String url,
        @Schema(description = "可选关键词清单（专有名词正确写法，纠正同音误写）；最多 50 条、单条最多 50 字",
                example = "[\"影视飓风\"]") List<String> keywords) {
}
