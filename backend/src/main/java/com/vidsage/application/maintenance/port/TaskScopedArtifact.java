package com.vidsage.application.maintenance.port;

import com.vidsage.domain.subtitle.SubtitleArtifact;

/**
 * 一条产物登记行 + 它的身份：对账要知道这个文件属于哪个任务、在哪个工作区目录里。
 *
 * <p>复用领域对象 {@link SubtitleArtifact} 而不是另造扁平 record——判据要读它的文件列与哈希列，
 * 领域对象已经有 {@code getFileName(kind)} / {@code getSha256(kind)} 这套访问器，
 * 另造一份等于把映射抄一遍。
 *
 * @param taskNo   对外任务号
 * @param dirName  工作区目录名（由 {@code workspace.source_key} 现算）
 * @param artifact 产物登记行（任何状态）
 */
public record TaskScopedArtifact(String taskNo, String dirName, SubtitleArtifact artifact) {
}
