package com.vidsage.application.maintenance.port;

import com.vidsage.domain.intermediate.IntermediateFile;

/**
 * 一条中间产物登记行 + 它的身份：与 {@link TaskScopedArtifact} 对称，带任务号与目录名。
 *
 * @param taskNo          对外任务号
 * @param dirName         工作区目录名（由 {@code workspace.source_key} 现算）
 * @param intermediateFile 中间产物登记行（任何状态）
 */
public record TaskScopedIntermediateFile(String taskNo, String dirName, IntermediateFile intermediateFile) {
}
