package com.vidsage.application.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vidsage.application.maintenance.port.ReconcileReader;
import com.vidsage.application.maintenance.port.TaskScopedArtifact;
import com.vidsage.application.maintenance.port.TaskScopedIntermediateFile;
import com.vidsage.application.maintenance.query.ReconcileIssue;
import com.vidsage.application.maintenance.query.ReconcileIssueGroup;
import com.vidsage.application.maintenance.query.ReconcileReport;
import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.domain.intermediate.IntermediateFile;
import com.vidsage.domain.intermediate.model.IntermediateFileType;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
import com.vidsage.shared.fs.ArtifactFileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ReconcileService} 的纯单元测试：{@link ReconcileReader} 与 {@link WorkspaceDirectoryManager}
 * 用 Mockito 替掉，四类判据各造命中与不命中场景。<b>会写文件</b>（④ 要真算哈希），故 {@code @TempDir}
 * 用 {@code CleanupMode.NEVER}。
 */
class ReconcileServiceTest {

    private static final String DIR = "BV1_1";
    private static final String TASK_NO = "t1";

    @TempDir(cleanup = CleanupMode.NEVER)
    Path root;

    private final ReconcileReader reader = mock(ReconcileReader.class);
    private final WorkspaceDirectoryManager dirManager = mock(WorkspaceDirectoryManager.class);

    @BeforeEach
    void pointWorkspaceRootAtTempDir() {
        when(dirManager.getWorkspaceRoot()).thenReturn(root);
    }

    // ---------- ① danglingFiles ----------

    @Test
    void reportsEachMissingRegisteredPositionAsOneDanglingRow() throws IOException {
        // READY ORIGINAL 登记 segments + subtitles，两个都不在盘上 → 一个文件位一行，共 2 行
        SubtitleArtifact artifact = readyArtifact(ArtifactType.ORIGINAL, null,
                "original-segments.json", "original.srt", null, null, null, null, null, null);

        ReconcileReport report = reconcile(List.of(scoped(artifact)), List.of());

        assertEquals(2, report.danglingFiles().count());
        assertEquals(List.of("original-segments.json", "original.srt"), fileNames(report.danglingFiles()));
        assertEquals(TASK_NO, report.danglingFiles().items().getFirst().taskNo());
    }

    @Test
    void doesNotReportDanglingWhenRegisteredFileIsOnDisk() throws IOException {
        writeFile(DIR, "original-segments.json", "segments");
        writeFile(DIR, "original.srt", "srt");
        SubtitleArtifact artifact = readyArtifact(ArtifactType.ORIGINAL, null,
                "original-segments.json", "original.srt", null, null, null, null, null, null);

        ReconcileReport report = reconcile(List.of(scoped(artifact)), List.of());

        assertEquals(0, report.danglingFiles().count());
    }

    @Test
    void missingRegisteredFileGoesToDanglingNotOrphan() throws IOException {
        // 同一个文件既在已登记集合里、又不在盘上 → 只进 ①，不进 ③
        SubtitleArtifact artifact = readyArtifact(ArtifactType.ORIGINAL, null,
                "original-segments.json", "original.srt", null, null, null, null, null, null);

        ReconcileReport report = reconcile(List.of(scoped(artifact)), List.of());

        assertEquals(2, report.danglingFiles().count());
        assertEquals(0, report.orphanFiles().count());
    }

    // ---------- ② reclaimPending ----------

    @Test
    void reportsReclaimPendingForSupersededArtifactWithFilesStillOnDisk() throws IOException {
        // 级联译文型：SUPERSEDED 但文件列留着（覆盖型换版会清空，这类不会）
        writeFile(DIR, "clean-segments.json", "a");
        writeFile(DIR, "clean.srt", "b");
        writeFile(DIR, "context.json", "c");
        SubtitleArtifact artifact = readyArtifact(ArtifactType.CLEANED, null,
                "clean-segments.json", "clean.srt", "context.json", null, null, null, null, null);
        artifact.supersede();

        ReconcileReport report = reconcile(List.of(scoped(artifact)), List.of());

        assertEquals(3, report.reclaimPending().count());
        assertEquals(0, report.orphanFiles().count(), "有登记的文件不算孤儿，②③ 不重叠");
    }

    @Test
    void reportsReclaimPendingForDeletedArtifactWithFilesStillOnDisk() throws IOException {
        writeFile(DIR, "clean-segments.json", "a");
        SubtitleArtifact artifact = readyArtifact(ArtifactType.ORIGINAL, null,
                "clean-segments.json", null, null, null, null, null, null, null);
        artifact.supersede();
        artifact.confirmDeletion();

        ReconcileReport report = reconcile(List.of(scoped(artifact)), List.of());

        assertEquals(1, report.reclaimPending().count());
        assertEquals("clean-segments.json", report.reclaimPending().items().getFirst().fileName());
    }

    @Test
    void doesNotReportReclaimPendingWhenSupersededColumnsAreCleared() {
        // 覆盖型换版已清空文件列（markSupersededAndClearFiles）→ ② 天然不报
        SubtitleArtifact cleared = SubtitleArtifact.restore(1L, 1L, 1L, ArtifactType.CLEANED, "", 1,
                SubtitleArtifactStatus.SUPERSEDED, null, null,
                null, null, null, null, null, null, null, null, LocalDateTime.now());

        ReconcileReport report = reconcile(List.of(scoped(cleared)), List.of());

        assertEquals(0, report.reclaimPending().count());
    }

    @Test
    void doesNotReportReclaimPendingWhenAReadyRowOwnsTheSameFileName() throws IOException {
        // 撞车：文件名不带版本号，盘上每个 (类型, 语言) 只有一份——旧行登记的名字和新版是同一个，
        // 盘上那份归新版本，不是待回收残留（照着报告去删会删掉当前版本）
        writeFile(DIR, "clean-segments.json", "a");
        SubtitleArtifact stale = readyArtifact(ArtifactType.CLEANED, null,
                "clean-segments.json", null, null, null, null, null, null, null);
        stale.supersede();
        SubtitleArtifact current = readyArtifact(ArtifactType.CLEANED, null,
                "clean-segments.json", null, null, null, null, null, null, null);

        ReconcileReport report = reconcile(List.of(scoped(stale), scoped(current)), List.of());

        assertEquals(0, report.reclaimPending().count(), "文件归当前有效版本，不算待回收");
        assertEquals(0, report.orphanFiles().count(), "它也不是孤儿");
    }

    @Test
    void reportsReclaimPendingForSupersededIntermediateFileOnDisk() throws IOException {
        writeFile(DIR, "source.mp4", "media");
        IntermediateFile file = intermediateFile("source.mp4", IntermediateFileType.SOURCE_MEDIA);
        file.supersede();

        ReconcileReport report = reconcile(List.of(), List.of(scoped(file)));

        assertEquals(1, report.reclaimPending().count());
        assertEquals(TASK_NO, report.reclaimPending().items().getFirst().taskNo());
        assertEquals(0, report.orphanFiles().count());
    }

    @Test
    void doesNotReportReclaimPendingForIntermediateFileAlreadyGone() {
        IntermediateFile file = intermediateFile("source.mp4", IntermediateFileType.SOURCE_MEDIA);
        file.supersede();
        file.confirmDeletion();

        ReconcileReport report = reconcile(List.of(), List.of(scoped(file)));

        assertEquals(0, report.reclaimPending().count());
    }

    // ---------- ③ orphanFiles ----------

    @Test
    void reportsOrphanForUnregisteredFileOnDisk() throws IOException {
        writeFile(DIR, "junk.srt", "x");

        ReconcileReport report = reconcile(List.of(), List.of());

        assertEquals(1, report.orphanFiles().count());
        ReconcileIssue orphan = report.orphanFiles().items().getFirst();
        assertEquals(DIR, orphan.dirName(), "③ 报的是目录名，不是任务号");
        assertEquals("junk.srt", orphan.fileName());
        assertNull(orphan.taskNo());
    }

    @Test
    void ignoresTransientSuffixesForOrphansButCountsThemInSummary() throws IOException {
        writeFile(DIR, "a.tmp", "x");
        writeFile(DIR, "b.part", "x");
        writeFile(DIR, "c.ytdl", "x");

        ReconcileReport report = reconcile(List.of(), List.of());

        assertEquals(0, report.orphanFiles().count(), "过渡态后缀是正常的，不算孤儿");
        assertEquals(1, report.summary().dirs());
        assertEquals(3, report.summary().files(), "summary.files 计的是全部普通文件数，含过渡态");
    }

    @Test
    void doesNotReportRegisteredFileAsOrphan() throws IOException {
        writeFile(DIR, "original-segments.json", "segments");
        SubtitleArtifact artifact = readyArtifact(ArtifactType.ORIGINAL, null,
                "original-segments.json", null, null, null, null, null, null, null);

        ReconcileReport report = reconcile(List.of(scoped(artifact)), List.of());

        assertEquals(0, report.orphanFiles().count());
    }

    // ---------- ④ hashMismatch ----------

    @Test
    void reportsHashMismatchWhenContentChanged() throws IOException {
        writeFile(DIR, "original-segments.json", "changed content");
        SubtitleArtifact artifact = readyArtifact(ArtifactType.ORIGINAL, null,
                "original-segments.json", null, null, null, "stale-sha256", null, null, null);

        ReconcileReport report = reconcile(List.of(scoped(artifact)), List.of());

        assertEquals(1, report.hashMismatch().count());
        assertEquals("original-segments.json", report.hashMismatch().items().getFirst().fileName());
        assertEquals(0, report.danglingFiles().count(), "文件在盘上，不进 ①");
    }

    @Test
    void doesNotReportHashMismatchWhenShaMatches() throws IOException {
        writeFile(DIR, "original-segments.json", "segments");
        String sha = ArtifactFileUtils.sha256(root.resolve(DIR).resolve("original-segments.json"));
        SubtitleArtifact artifact = readyArtifact(ArtifactType.ORIGINAL, null,
                "original-segments.json", null, null, null, sha, null, null, null);

        ReconcileReport report = reconcile(List.of(scoped(artifact)), List.of());

        assertEquals(0, report.hashMismatch().count());
    }

    @Test
    void doesNotReportHashMismatchWhenShaColumnIsNull() throws IOException {
        // 可选位没登记文件时文件名与哈希同时为空（同生同灭）；这里文件名有值而哈希为空，同样跳过
        writeFile(DIR, "original-segments.json", "segments");
        SubtitleArtifact artifact = readyArtifact(ArtifactType.ORIGINAL, null,
                "original-segments.json", null, null, null, null, null, null, null);

        ReconcileReport report = reconcile(List.of(scoped(artifact)), List.of());

        assertEquals(0, report.hashMismatch().count());
    }

    @Test
    void missingFileGoesToDanglingNotHashMismatch() {
        SubtitleArtifact artifact = readyArtifact(ArtifactType.ORIGINAL, null,
                "original-segments.json", null, null, null, "some-sha256", null, null, null);

        ReconcileReport report = reconcile(List.of(scoped(artifact)), List.of());

        assertEquals(1, report.danglingFiles().count());
        assertEquals(0, report.hashMismatch().count(), "文件不在的位归 ① 报，④ 只管内容不对");
    }

    // ---------- 汇总与空态 ----------

    @Test
    void countsDirsAndFilesAcrossSubdirectories() throws IOException {
        writeFile(DIR, "a.json", "x");
        writeFile("BV2_1", "b.json", "y");
        writeFile("BV2_1", "c.srt", "z");

        ReconcileReport report = reconcile(List.of(), List.of());

        assertEquals(2, report.summary().dirs());
        assertEquals(3, report.summary().files());
        assertEquals(3, report.orphanFiles().count());
    }

    @Test
    void returnsAllZeroForEmptyWorkspace() {
        ReconcileReport report = reconcile(List.of(), List.of());

        assertEquals(0, report.summary().dirs());
        assertEquals(0, report.summary().files());
        assertEquals(0, report.danglingFiles().count());
        assertEquals(0, report.reclaimPending().count());
        assertEquals(0, report.orphanFiles().count());
        assertEquals(0, report.hashMismatch().count());
        assertTrue(report.danglingFiles().items().isEmpty());
    }

    // ---------- 辅助 ----------

    private ReconcileReport reconcile(List<TaskScopedArtifact> artifacts,
                                      List<TaskScopedIntermediateFile> intermediateFiles) {
        when(reader.readAllArtifacts()).thenReturn(artifacts);
        when(reader.readAllIntermediateFiles()).thenReturn(intermediateFiles);
        when(reader.listWorkspaceFilesByDirName()).thenReturn(listDisk());
        return new ReconcileService(reader, dirManager).reconcile();
    }

    /** 盘上清单按真实文件系统造，保证与 ①②④ 的 {@code isRegularFile} 判据一致。 */
    private Map<String, List<String>> listDisk() {
        Map<String, List<String>> filesByDirName = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return filesByDirName;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                try (Stream<Path> files = Files.list(dir)) {
                    filesByDirName.put(dir.getFileName().toString(),
                            files.filter(Files::isRegularFile)
                                    .map(path -> path.getFileName().toString())
                                    .sorted()
                                    .toList());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return filesByDirName;
    }

    private void writeFile(String dirName, String fileName, String content) throws IOException {
        Path dir = root.resolve(dirName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), content);
    }

    private static TaskScopedArtifact scoped(SubtitleArtifact artifact) {
        return new TaskScopedArtifact(TASK_NO, DIR, artifact);
    }

    private static TaskScopedIntermediateFile scoped(IntermediateFile file) {
        return new TaskScopedIntermediateFile(TASK_NO, DIR, file);
    }

    private static List<String> fileNames(ReconcileIssueGroup group) {
        return group.items().stream().map(ReconcileIssue::fileName).toList();
    }

    private static SubtitleArtifact readyArtifact(ArtifactType type, String lang,
            String segments, String subtitles, String context, String terms,
            String segmentsSha, String subtitlesSha, String contextSha, String termsSha) {
        SubtitleArtifact artifact = SubtitleArtifact.createCreatingArtifact(1L, 1L, type, lang, null, null);
        artifact.completeCreation(segments, subtitles, context, terms,
                segmentsSha, subtitlesSha, contextSha, termsSha);
        return artifact;
    }

    private static IntermediateFile intermediateFile(String fileName, IntermediateFileType type) {
        return IntermediateFile.ofReady(1L, fileName, type, 1L);
    }
}
