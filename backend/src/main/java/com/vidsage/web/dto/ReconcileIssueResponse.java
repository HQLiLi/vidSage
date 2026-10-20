package com.vidsage.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vidsage.application.maintenance.query.ReconcileIssue;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 对账报告里的一行问题：任务号（①②④）或目录名（③）+ 文件名。
 *
 * <p>「问题类别」由它所属的数组表达（{@code danglingFiles} / {@code reclaimPending} / …），
 * 不再冗余一个 {@code category} 字段。①②④ 的行没有 {@code dirName}、③ 的行没有 {@code taskNo}，
 * 空的那一项<b>不出现在 JSON 里</b>（{@code NON_NULL}）。
 *
 * @param taskNo   对外任务号；③ 的孤儿不在表里，没有任务号可带，此项为 {@code null}
 * @param dirName  工作区目录名；①②④ 的行有任务号，此项为 {@code null}
 * @param fileName 工作区内文件名（不是绝对路径）
 */
@Schema(description = "对账报告里的一行问题：任务号（①②④）或目录名（③）+ 文件名")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReconcileIssueResponse(
        @Schema(description = "对外任务号；③ 的孤儿没有任务号，此项为 null", nullable = true,
                example = "3f2a9c1e9b0d4a2f8c7e6d5b4a392817")
        String taskNo,
        @Schema(description = "工作区目录名；①②④ 的行有任务号，此项为 null", nullable = true,
                example = "BV11QMd6WEJp_p1")
        String dirName,
        @Schema(description = "工作区内文件名", example = "cleaned-segments.json")
        String fileName) {

    /**
     * 应用层读模型 → 对外响应的纯映射（无业务逻辑）：字段一一对应。
     *
     * @param issue 应用层读模型
     * @return 对外响应
     */
    public static ReconcileIssueResponse from(ReconcileIssue issue) {
        return new ReconcileIssueResponse(issue.taskNo(), issue.dirName(), issue.fileName());
    }
}
