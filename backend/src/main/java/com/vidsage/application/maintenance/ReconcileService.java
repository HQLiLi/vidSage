package com.vidsage.application.maintenance;

import com.vidsage.application.maintenance.port.ReconcileReader;
import com.vidsage.application.maintenance.port.TaskScopedArtifact;
import com.vidsage.application.maintenance.port.TaskScopedIntermediateFile;
import com.vidsage.application.maintenance.query.ReconcileIssue;
import com.vidsage.application.maintenance.query.ReconcileIssueGroup;
import com.vidsage.application.maintenance.query.ReconcileReport;
import com.vidsage.application.maintenance.query.ReconcileSummary;
import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.domain.intermediate.IntermediateFile;
import com.vidsage.domain.intermediate.model.IntermediateFileStatus;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactFileKind;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
import com.vidsage.shared.fs.ArtifactFileUtils;
import com.vidsage.shared.fs.TransientFiles;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 只读对账：全量扫工作区目录与两张文件表，把不一致四件事全报。
 *
 * <p>四类：① 表 {@code READY} 但文件不在；② 表 {@code SUPERSEDED} / {@code DELETED} 但登记的文件
 * 还在盘上；③ 盘上有文件、表里没有任何登记、后缀不在白名单；④ 表 {@code READY} 但文件哈希对不上。
 * <b>只报不修</b>——不删文件、不改状态，天然幂等。
 *
 * <p>四类互斥：②③ 由「在不在已登记集合里」切开，①④ 由「文件在不在」切开，同一个文件不会同时进两个数组。
 */
@Slf4j
@Service
public class ReconcileService {

    private final ReconcileReader reconcileReader;
    private final WorkspaceDirectoryManager workspaceDirectoryManager;

    public ReconcileService(ReconcileReader reconcileReader,
                            WorkspaceDirectoryManager workspaceDirectoryManager) {
        this.reconcileReader = reconcileReader;
        this.workspaceDirectoryManager = workspaceDirectoryManager;
    }

    /**
     * 跑一次全量对账，返回完整报告。
     *
     * @return 四类不一致 + 扫描时刻 + 扫到的目录/文件数
     */
    public ReconcileReport reconcile() {
        long start = System.currentTimeMillis();
        LocalDateTime checkedAt = LocalDateTime.now();
        Path root = workspaceDirectoryManager.getWorkspaceRoot();
        Map<String, List<String>> filesByDirName = reconcileReader.listWorkspaceFilesByDirName();
        List<TaskScopedArtifact> artifacts = reconcileReader.readAllArtifacts();
        List<TaskScopedIntermediateFile> intermediateFiles = reconcileReader.readAllIntermediateFiles();

        List<ReconcileIssue> danglingFiles = findDanglingFiles(artifacts, root);
        List<ReconcileIssue> reclaimPending = findReclaimPending(artifacts, intermediateFiles, root);
        List<ReconcileIssue> orphanFiles = findOrphanFiles(artifacts, intermediateFiles, filesByDirName);
        List<ReconcileIssue> hashMismatch = findHashMismatch(artifacts, root);

        int dirs = filesByDirName.size();
        int files = filesByDirName.values().stream().mapToInt(List::size).sum();
        // 脱敏：只记汇总（四类条数 + 目录/文件数 + 耗时），不打目录名、不打文件名、不打路径
        log.info("对账完成: dangling={}, reclaimPending={}, orphan={}, hashMismatch={}, dirs={}, files={}, cost={}ms",
                danglingFiles.size(), reclaimPending.size(), orphanFiles.size(), hashMismatch.size(),
                dirs, files, System.currentTimeMillis() - start);
        return new ReconcileReport(checkedAt, new ReconcileSummary(dirs, files),
                ReconcileIssueGroup.of(danglingFiles), ReconcileIssueGroup.of(reclaimPending),
                ReconcileIssueGroup.of(orphanFiles), ReconcileIssueGroup.of(hashMismatch));
    }

    /**
     * ① 表 {@code READY} 但文件不在：对该类型应带的每个「文件列非空」的位，盘上不是普通文件就报一行。
     * 可选位没登记（列是 {@code null}）不算缺；登记了却不在盘上就是异常（与 {@code isIntact} 同口径）。
     */
    private static List<ReconcileIssue> findDanglingFiles(List<TaskScopedArtifact> artifacts, Path root) {
        List<ReconcileIssue> issues = new ArrayList<>();
        for (TaskScopedArtifact scoped : artifacts) {
            SubtitleArtifact artifact = scoped.artifact();
            if (artifact.getStatus() != SubtitleArtifactStatus.READY) {
                continue;
            }
            for (ArtifactFileKind kind : ArtifactFileKind.values()) {
                if (!kind.belongsTo(artifact.getArtifactType())) {
                    continue;
                }
                String fileName = artifact.getFileName(kind);
                if (fileName != null && !ArtifactFileUtils.isRegularFile(resolveInWorkspace(root, scoped, fileName))) {
                    issues.add(ReconcileIssue.ofTask(scoped.taskNo(), fileName));
                }
            }
        }
        return issues;
    }

    /**
     * ② 表 {@code SUPERSEDED} / {@code DELETED} 但登记的文件还在盘上：只报登记里写着的文件
     * （列是 {@code null} 就不报），盘上存在就报一行。两个来源都进同一个数组。
     *
     * <p><b>已经被当前有效版本占着的文件名不报</b>：文件名不带版本号、盘上每个 (类型, 语言) 只有一份，
     * 旧行登记的名字和新版是同一个。所以只要该文件名被一条 {@code READY} 行登记着，盘上那份就是它的，
     * 旧行喊「待回收」是撞车——照着报告去删，删掉的是当前版本。
     */
    private static List<ReconcileIssue> findReclaimPending(List<TaskScopedArtifact> artifacts,
                                                          List<TaskScopedIntermediateFile> intermediateFiles,
                                                          Path root) {
        Set<RegisteredFile> ownedByReady = collectReadyOwnedFiles(artifacts);
        List<ReconcileIssue> issues = new ArrayList<>();
        for (TaskScopedArtifact scoped : artifacts) {
            SubtitleArtifact artifact = scoped.artifact();
            if (artifact.getStatus() != SubtitleArtifactStatus.SUPERSEDED
                    && artifact.getStatus() != SubtitleArtifactStatus.DELETED) {
                continue;
            }
            for (ArtifactFileKind kind : ArtifactFileKind.values()) {
                if (!kind.belongsTo(artifact.getArtifactType())) {
                    continue;
                }
                String fileName = artifact.getFileName(kind);
                if (fileName != null && !ownedByReady.contains(new RegisteredFile(scoped.dirName(), fileName))
                        && ArtifactFileUtils.isRegularFile(resolveInWorkspace(root, scoped, fileName))) {
                    issues.add(ReconcileIssue.ofTask(scoped.taskNo(), fileName));
                }
            }
        }
        for (TaskScopedIntermediateFile scoped : intermediateFiles) {
            IntermediateFile file = scoped.intermediateFile();
            if (file.getStatus() != IntermediateFileStatus.SUPERSEDED
                    && file.getStatus() != IntermediateFileStatus.DELETED) {
                continue;
            }
            if (ArtifactFileUtils.isRegularFile(root.resolve(scoped.dirName()).resolve(file.getFileName()))) {
                issues.add(ReconcileIssue.ofTask(scoped.taskNo(), file.getFileName()));
            }
        }
        return issues;
    }

    /**
     * ③ 盘上有文件、表里没有任何登记、后缀不在白名单：扫盘上每个一层子目录下的每个普通文件，
     * 不在「已登记集合」（两张表任何状态）里、且后缀不是过渡态，就报一行（带目录名）。
     */
    private static List<ReconcileIssue> findOrphanFiles(List<TaskScopedArtifact> artifacts,
                                                        List<TaskScopedIntermediateFile> intermediateFiles,
                                                        Map<String, List<String>> filesByDirName) {
        Set<RegisteredFile> registered = collectRegisteredFiles(artifacts, intermediateFiles);

        List<ReconcileIssue> issues = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : filesByDirName.entrySet()) {
            for (String fileName : entry.getValue()) {
                if (TransientFiles.isTransient(fileName)
                        || registered.contains(new RegisteredFile(entry.getKey(), fileName))) {
                    continue;
                }
                issues.add(ReconcileIssue.ofDir(entry.getKey(), fileName));
            }
        }
        return issues;
    }

    /**
     * ④ 表 {@code READY} 但文件哈希对不上：只比对「文件列与哈希列都非空」的位，且文件在盘上。
     * 哈希列为 {@code null} 就跳过——可选位没登记文件时，文件名与哈希同时为空（同生同灭）。
     */
    private static List<ReconcileIssue> findHashMismatch(List<TaskScopedArtifact> artifacts, Path root) {
        List<ReconcileIssue> issues = new ArrayList<>();
        for (TaskScopedArtifact scoped : artifacts) {
            SubtitleArtifact artifact = scoped.artifact();
            if (artifact.getStatus() != SubtitleArtifactStatus.READY) {
                continue;
            }
            for (ArtifactFileKind kind : ArtifactFileKind.values()) {
                if (!kind.belongsTo(artifact.getArtifactType())) {
                    continue;
                }
                String fileName = artifact.getFileName(kind);
                String registeredSha256 = artifact.getSha256(kind);
                if (fileName == null || registeredSha256 == null) {
                    continue;
                }
                Path file = resolveInWorkspace(root, scoped, fileName);
                // 文件不在的位归 ① 报，④ 只管「文件在、内容不对」
                if (!ArtifactFileUtils.isRegularFile(file)) {
                    continue;
                }
                if (!registeredSha256.equals(ArtifactFileUtils.sha256(file))) {
                    issues.add(ReconcileIssue.ofTask(scoped.taskNo(), fileName));
                }
            }
        }
        return issues;
    }

    /**
     * 已登记文件集合（③ 的参照）：两张表<b>任何状态</b>行里非空的文件列，按 (目录名, 文件名) 收。
     *
     * <p>「任何状态」是要点——只要表里还登记着，这个文件就有主，残留归 ② 报，不算孤儿。
     */
    private static Set<RegisteredFile> collectRegisteredFiles(List<TaskScopedArtifact> artifacts,
                                                             List<TaskScopedIntermediateFile> intermediateFiles) {
        Set<RegisteredFile> registered = new HashSet<>();
        for (TaskScopedArtifact scoped : artifacts) {
            SubtitleArtifact artifact = scoped.artifact();
            for (ArtifactFileKind kind : ArtifactFileKind.values()) {
                if (!kind.belongsTo(artifact.getArtifactType())) {
                    continue;
                }
                String fileName = artifact.getFileName(kind);
                if (fileName != null) {
                    registered.add(new RegisteredFile(scoped.dirName(), fileName));
                }
            }
        }
        for (TaskScopedIntermediateFile scoped : intermediateFiles) {
            registered.add(new RegisteredFile(scoped.dirName(), scoped.intermediateFile().getFileName()));
        }
        return registered;
    }

    /**
     * 当前有效版本「占着」的文件：{@code READY} 行登记的那些，按 (目录名, 文件名) 收。
     *
     * <p>② 用它排除撞车：文件名不带版本号，盘上每个 (类型, 语言) 只有一份，旧行登记的名字和新版是同一个
     * ——只要这个文件名被一条 {@code READY} 行登记着，盘上那份就归它，不该按「待回收」报。
     *
     * <p>只收产物表：中间产物的 {@code file_name} 是唯一键的一部分，一个文件一个 task 只有一行，
     * 不存在「旧行与新版登记同一个名字」，天然不撞车。
     */
    private static Set<RegisteredFile> collectReadyOwnedFiles(List<TaskScopedArtifact> artifacts) {
        Set<RegisteredFile> owned = new HashSet<>();
        for (TaskScopedArtifact scoped : artifacts) {
            SubtitleArtifact artifact = scoped.artifact();
            if (artifact.getStatus() != SubtitleArtifactStatus.READY) {
                continue;
            }
            for (ArtifactFileKind kind : ArtifactFileKind.values()) {
                if (!kind.belongsTo(artifact.getArtifactType())) {
                    continue;
                }
                String fileName = artifact.getFileName(kind);
                if (fileName != null) {
                    owned.add(new RegisteredFile(scoped.dirName(), fileName));
                }
            }
        }
        return owned;
    }

    /**
     * 定位产物文件：工作区根 / 目录名 / 文件名。
     */
    private static Path resolveInWorkspace(Path root, TaskScopedArtifact scoped, String fileName) {
        return root.resolve(scoped.dirName()).resolve(fileName);
    }

    /**
     * 已登记集合里的一个成员：目录名 + 文件名（用它当 {@code HashSet} 的键）。
     */
    private record RegisteredFile(String dirName, String fileName) {
    }
}
