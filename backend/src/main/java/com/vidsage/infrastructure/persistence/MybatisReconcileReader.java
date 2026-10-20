package com.vidsage.infrastructure.persistence;

import com.vidsage.application.maintenance.port.ReconcileReader;
import com.vidsage.application.maintenance.port.TaskScopedArtifact;
import com.vidsage.application.maintenance.port.TaskScopedIntermediateFile;
import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.intermediate.IntermediateFile;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.WorkspaceDir;
import com.vidsage.infrastructure.persistence.entity.IntermediateFileEntity;
import com.vidsage.infrastructure.persistence.entity.SubtitleArtifactEntity;
import com.vidsage.infrastructure.persistence.entity.SubtitleTaskEntity;
import com.vidsage.infrastructure.persistence.entity.WorkspaceEntity;
import com.vidsage.infrastructure.persistence.mapper.IntermediateFileMapper;
import com.vidsage.infrastructure.persistence.mapper.SubtitleArtifactMapper;
import com.vidsage.infrastructure.persistence.mapper.SubtitleTaskMapper;
import com.vidsage.infrastructure.persistence.mapper.WorkspaceMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Repository;

/**
 * {@link ReconcileReader} 的 MyBatis-Plus 实现：四张表各一次 {@code selectList(null)} 全表读，
 * 在内存里 join（个人项目数据量小，几万行级别；不写 join SQL 就少一处维护）。
 *
 * <p>目录名由 {@code WorkspaceDir.of(SourceKey.of(source_key))} 现算，与写路径同一公式。
 * 盘上清单用 {@code Files.list}（只一层、{@code isRegularFile} 过滤），根路径来自
 * {@link WorkspaceDirectoryManager#getWorkspaceRoot()}——<b>不再注入 {@code WorkspaceProperties}</b>，
 * 免得两处各算一次根、日后慢慢分叉。
 *
 * <p>本类是 {@code infrastructure → application} 的实现端口依赖，与 {@code ProcessToolDiagnostics} 同款。
 */
@Repository
public class MybatisReconcileReader implements ReconcileReader {

    private final SubtitleArtifactMapper artifactMapper;
    private final IntermediateFileMapper intermediateFileMapper;
    private final SubtitleTaskMapper subtitleTaskMapper;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceDirectoryManager workspaceDirectoryManager;

    public MybatisReconcileReader(SubtitleArtifactMapper artifactMapper,
                                  IntermediateFileMapper intermediateFileMapper,
                                  SubtitleTaskMapper subtitleTaskMapper,
                                  WorkspaceMapper workspaceMapper,
                                  WorkspaceDirectoryManager workspaceDirectoryManager) {
        this.artifactMapper = artifactMapper;
        this.intermediateFileMapper = intermediateFileMapper;
        this.subtitleTaskMapper = subtitleTaskMapper;
        this.workspaceMapper = workspaceMapper;
        this.workspaceDirectoryManager = workspaceDirectoryManager;
    }

    @Override
    public List<TaskScopedArtifact> readAllArtifacts() {
        Map<Long, TaskIdentity> identities = readTaskIdentities();
        return artifactMapper.selectList(null).stream()
                .map(entity -> scopeArtifact(entity, identities))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public List<TaskScopedIntermediateFile> readAllIntermediateFiles() {
        Map<Long, TaskIdentity> identities = readTaskIdentities();
        return intermediateFileMapper.selectList(null).stream()
                .map(entity -> scopeIntermediateFile(entity, identities))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public Map<String, List<String>> listWorkspaceFilesByDirName() {
        Path root = workspaceDirectoryManager.getWorkspaceRoot();
        if (!Files.isDirectory(root)) {
            // 工作区还没建过是合法状态：报告该给「四类全 0 + dirs: 0」，不是抛异常
            return Map.of();
        }
        Map<String, List<String>> filesByDirName = new LinkedHashMap<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                filesByDirName.put(dir.getFileName().toString(), listRegularFileNames(dir));
            }
        } catch (IOException e) {
            // 脱敏：消息不带完整路径 / 目录名（目录名内嵌身份键）
            throw new IllegalStateException("扫描工作区根目录失败（" + e.getClass().getSimpleName()
                    + "）：请检查工作区根是否存在且可读", e);
        }
        return filesByDirName;
    }

    /**
     * 列一个目录下的普通文件名（不含子目录、不递归）。
     */
    private static List<String> listRegularFileNames(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("扫描工作区子目录失败（" + e.getClass().getSimpleName()
                    + "）：请检查该目录是否可读", e);
        }
    }

    /**
     * 读全部任务身份：{@code taskId → (taskNo, 目录名)}。目录名由 {@code workspace.source_key} 现算。
     */
    private Map<Long, TaskIdentity> readTaskIdentities() {
        Map<Long, String> dirNameByWorkspaceId = new HashMap<>();
        for (WorkspaceEntity workspace : workspaceMapper.selectList(null)) {
            dirNameByWorkspaceId.put(workspace.getId(),
                    WorkspaceDir.of(new SourceKey(workspace.getSourceKey())).value());
        }
        Map<Long, TaskIdentity> identities = new HashMap<>();
        for (SubtitleTaskEntity task : subtitleTaskMapper.selectList(null)) {
            String dirName = dirNameByWorkspaceId.get(task.getWorkspaceId());
            if (dirName != null) {
                identities.put(task.getId(), new TaskIdentity(task.getTaskNo(), dirName));
            }
        }
        return identities;
    }

    /**
     * 给一条产物登记行补上任务号与目录名；任务身份缺失（数据自相矛盾）时返回空，跳过这一行。
     */
    private static Optional<TaskScopedArtifact> scopeArtifact(SubtitleArtifactEntity entity,
                                                             Map<Long, TaskIdentity> identities) {
        TaskIdentity identity = identities.get(entity.getTaskId());
        return identity == null ? Optional.empty()
                : Optional.of(new TaskScopedArtifact(identity.taskNo(), identity.dirName(), toDomain(entity)));
    }

    /**
     * 给一条中间产物登记行补上任务号与目录名；任务身份缺失时返回空，跳过这一行。
     */
    private static Optional<TaskScopedIntermediateFile> scopeIntermediateFile(
            IntermediateFileEntity entity, Map<Long, TaskIdentity> identities) {
        TaskIdentity identity = identities.get(entity.getTaskId());
        return identity == null ? Optional.empty()
                : Optional.of(new TaskScopedIntermediateFile(identity.taskNo(), identity.dirName(),
                        toDomain(entity)));
    }

    private static SubtitleArtifact toDomain(SubtitleArtifactEntity entity) {
        return SubtitleArtifact.restore(entity.getId(), entity.getTaskId(), entity.getDerivedFromRunId(),
                entity.getArtifactType(), entity.getLang(), entity.getRevision(), entity.getStatus(),
                entity.getDerivedFromArtifactId(), entity.getKeywordsFingerprint(), entity.getSegmentsFileName(),
                entity.getSubtitlesFileName(), entity.getContextFileName(), entity.getTermsFileName(),
                entity.getSegmentsSha256(), entity.getSubtitlesSha256(), entity.getContextSha256(),
                entity.getTermsSha256(), entity.getCreatedAt());
    }

    private static IntermediateFile toDomain(IntermediateFileEntity entity) {
        return IntermediateFile.restore(entity.getId(), entity.getTaskId(), entity.getFileName(),
                entity.getFileType(), entity.getStatus(), entity.getDerivedFromRunId(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    /**
     * 一条任务的身份：对外任务号 + 工作区目录名。
     */
    private record TaskIdentity(String taskNo, String dirName) {
    }
}
