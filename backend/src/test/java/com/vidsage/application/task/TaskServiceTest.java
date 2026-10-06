package com.vidsage.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.application.media.WorkspaceService;
import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.application.task.query.TaskArtifactFile;
import com.vidsage.application.task.query.TaskSnapshot;
import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.shared.WorkspaceDir;
import com.vidsage.domain.shared.WorkspaceKey;
import com.vidsage.domain.task.TaskNo;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.shared.StageEvent;
import com.vidsage.domain.task.TaskInputType;
import com.vidsage.domain.task.TaskStatus;
import com.vidsage.domain.task.TranslationStatus;
import com.vidsage.domain.task.VideoTaskRepository;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * {@link TaskService} 的纯单元测试：仓储与管线用 Mockito mock，工作区用真实实例 + 临时目录
 * （目录公式单源在 domain/shared，{@code WorkspaceService} 真实落盘——真跑比 mock 更能钉住路径约定）。
 *
 * <p>覆盖「URL 路线落库与启动管线」「上传路线流式落盘按内容寻址」「查重 409 与孤儿文件清理」「查询」。
 */
class TaskServiceTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private final VideoTaskRepository repository = org.mockito.Mockito.mock(VideoTaskRepository.class);
    private final TaskPipelineService pipelineService = org.mockito.Mockito.mock(TaskPipelineService.class);

    /**
     * 工作区用真实实例（目录公式单源在 domain/shared，workspaceFor 真实落盘，真跑比 mock
     * 更能钉住路径约定）。{@code @TempDir} 在字段初始化之后才注入，故只能方法内构造，
     * 不能在字段初始化器里引用。
     */
    private TaskService service() {
        return new TaskService(repository,
                new WorkspaceService(new WorkspaceProperties(tempDir.resolve("works").toString())),
                pipelineService);
    }

    @org.junit.jupiter.api.BeforeEach
    void stubInsertToEchoBack() {
        // repository.insert 的 mock 默认返回 null；真实 MP insert 会回填自增 id——
        // 不关心 id 的用例用「原样回显」代替，个别用例可再覆盖
        when(repository.insert(any(VideoTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---------- URL 路线 ----------

    @Test
    void submitUrlInsertsPendingTaskAndStartsPipeline() {
        when(repository.findActiveByWorkspaceKey(any())).thenReturn(Optional.empty());

        TaskSnapshot view = service().submitUrl("https://example.com/watch/1");

        ArgumentCaptor<VideoTask> captor = ArgumentCaptor.forClass(VideoTask.class);
        verify(repository).insert(captor.capture());
        VideoTask inserted = captor.getValue();
        assertEquals(TaskInputType.URL, inserted.getInputType());
        assertEquals("https://example.com/watch/1", inserted.getSourceUri());
        assertEquals(TaskStatus.PENDING, inserted.getStatus());
        assertTrue(inserted.getTaskNo().value().matches("[0-9a-f]{32}"));
        // 目录名与 WorkspaceDir 同公式：sanitize(url)-hash12（非法字符替换为下划线）
        WorkspaceKey expectedKey = WorkspaceKey.ofHash(WorkspaceService.normalize(
                new com.vidsage.domain.media.MediaInput.RemoteUrl("https://example.com/watch/1")).value());
        String expectedDir = WorkspaceDir.sanitizeName("https://example.com/watch/1") + "-" + expectedKey.value();
        assertEquals(expectedDir, inserted.getWorkspaceDir());

        verify(pipelineService).execute(inserted.getTaskNo());
        assertEquals(inserted.getTaskNo().value(), view.taskNo());
        assertEquals(TaskStatus.PENDING, view.status());
        assertEquals(TaskInputType.URL, view.inputType());
    }

    @Test
    void submitUrlRejectsBlankUrlWithoutTouchingRepository() {
        assertThrows(IllegalArgumentException.class, () -> service().submitUrl("   "));

        verify(repository, never()).insert(any());
        verify(pipelineService, never()).execute(any());
    }

    @Test
    void submitUrlConflictsWithActiveTaskAndCarriesExistingView() {
        VideoTask existing = VideoTask.newPending(TaskInputType.URL, "https://example.com/watch/1",
                new WorkspaceKey("a1b2c3d4e5f6"), "dir");
        when(repository.findActiveByWorkspaceKey(any())).thenReturn(Optional.of(existing));

        TaskConflictException e = assertThrows(TaskConflictException.class,
                () -> service().submitUrl("https://example.com/watch/1"));

        assertEquals(existing.getTaskNo().value(), e.getExistingTask().taskNo());
        assertEquals(TaskStatus.PENDING, e.getExistingTask().status());
        assertTrue(e.getMessage().contains("请勿重复提交"));
        verify(repository, never()).insert(any());
        verify(pipelineService, never()).execute(any());
    }

    // ---------- 上传路线 ----------

    @Test
    void submitUploadStreamsToContentAddressedWorkspace() throws Exception {
        when(repository.findActiveByWorkspaceKey(any())).thenReturn(Optional.empty());
        byte[] media = "fake-video-bytes-0123456789".getBytes(StandardCharsets.UTF_8);
        String expectedKey = sha256Prefix(media);

        TaskSnapshot view = service().submitUpload(new ByteArrayInputStream(media), "我的 视频.mp4");

        Path dir = tempDir.resolve("works").resolve("我的 视频.mp4-" + expectedKey);
        Path source = dir.resolve("source.mp4");
        assertTrue(Files.isDirectory(dir), () -> "应按 sanitize(原名)-<内容 key> 建工作区：" + dir);
        assertTrue(Files.isRegularFile(source), "归位后应有 source.mp4");
        // 边写边算：文件内容与上传流逐字节一致
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(media)),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));

        ArgumentCaptor<VideoTask> captor = ArgumentCaptor.forClass(VideoTask.class);
        verify(repository).insert(captor.capture());
        VideoTask inserted = captor.getValue();
        assertEquals(TaskInputType.UPLOAD, inserted.getInputType());
        assertEquals("我的 视频.mp4", inserted.getSourceUri());
        assertEquals(expectedKey, inserted.getWorkspaceKey().value());
        assertEquals("我的 视频.mp4-" + expectedKey, inserted.getWorkspaceDir());
        verify(pipelineService).execute(inserted.getTaskNo());
        assertEquals(inserted.getTaskNo().value(), view.taskNo());
    }

    @Test
    void submitUploadReusesSameDirectoryForSameContentAndFallsBackLabelForBlankName() throws Exception {
        when(repository.findActiveByWorkspaceKey(any())).thenReturn(Optional.empty());
        byte[] media = "same-content".getBytes(StandardCharsets.UTF_8);

        service().submitUpload(new ByteArrayInputStream(media), "  ");
        service().submitUpload(new ByteArrayInputStream(media), "  ");

        Path dir = tempDir.resolve("works").resolve(
                WorkspaceDir.FALLBACK_NAME + "-" + sha256Prefix(media));
        assertTrue(Files.isRegularFile(dir.resolve("source.mp4")), "空白原始名应回退 media 标签并照常归位");
    }

    @Test
    void submitUploadConflictCleansTempFileAndLeavesNoOrphanDirectory() throws Exception {
        VideoTask existing = VideoTask.newPending(TaskInputType.UPLOAD, "a.mp4", new WorkspaceKey("a1b2c3d4e5f6"), "dir");
        when(repository.findActiveByWorkspaceKey(any())).thenReturn(Optional.of(existing));
        byte[] media = "duplicate-content".getBytes(StandardCharsets.UTF_8);

        assertThrows(TaskConflictException.class,
                () -> service().submitUpload(new ByteArrayInputStream(media), "a.mp4"));

        Path root = tempDir.resolve("works");
        try (Stream<Path> entries = Files.list(root)) {
            List<Path> leftovers = entries
                    .filter(p -> p.getFileName().toString().endsWith(".part"))
                    .toList();
            assertTrue(leftovers.isEmpty(), () -> "冲突后不得残留临时文件：" + leftovers);
        }
        assertFalse(Files.exists(root.resolve("a.mp4-" + sha256Prefix(media))),
                "冲突发生在建工作区之前，不得留下无任务的孤儿目录");
        verify(repository, never()).insert(any());
    }

    @Test
    void submitUploadRejectsNullStream() {
        assertThrows(IllegalArgumentException.class, () -> service().submitUpload(null, "a.mp4"));
    }

    // ---------- 查询 ----------

    @Test
    void findByTaskNoReturnsViewWithoutInternalFields() {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "demo.mp4", new WorkspaceKey("a1b2c3d4e5f6"), "dir");
        task.start();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        TaskSnapshot view = service().findByTaskNo(task.getTaskNo().value());

        assertEquals(task.getTaskNo().value(), view.taskNo());
        assertEquals(TaskStatus.RUNNING, view.status());
        assertEquals(TaskInputType.UPLOAD, view.inputType());
        assertEquals("demo.mp4", view.sourceUri());
        assertEquals(task.getStartedAt(), view.startedAt());
    }

    @Test
    void findByTaskNoThrowsWithActionableMessageWhenMissing() {
        when(repository.findByTaskNo(new TaskNo("nope"))).thenReturn(Optional.empty());

        TaskNotFoundException e = assertThrows(TaskNotFoundException.class,
                () -> service().findByTaskNo("nope"));

        assertTrue(e.getMessage().contains("任务不存在"), e.getMessage());
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
    }

    @Test
    void findByTaskNoRejectsBlankTaskNo() {
        assertThrows(IllegalArgumentException.class, () -> service().findByTaskNo(" "));
    }

    // ---------- SSE 快照 ----------

    @Test
    void snapshotForPendingTaskWithoutStageHasNullStage() {
        VideoTask task = VideoTask.newPending(TaskInputType.URL, "https://example.com/v",
                new WorkspaceKey("a1b2c3d4e5f6"), "dir-a1b2c3d4e5f6");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        TaskProgressMessage event = service().snapshot(task.getTaskNo().value());

        assertEquals(task.getTaskNo().value(), event.taskNo());
        assertEquals(TaskStatus.PENDING, event.taskStatus());
        assertNull(event.stage(), "尚无阶段事件时 stage 为 null，形状仍合法");
    }

    @Test
    void snapshotForRunningTaskCarriesRunningStageEvent() {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "demo.mp4", new WorkspaceKey("a1b2c3d4e5f6"), "dir");
        task.start();
        task.updateStage("transcribe");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        TaskProgressMessage event = service().snapshot(task.getTaskNo().value());

        assertEquals(TaskStatus.RUNNING, event.taskStatus());
        assertEquals("transcribe", event.stage().stage());
        assertEquals(0, event.stage().progress(), "快照只保证形状合法，精确进度由增量事件携带");
        assertEquals(StageEvent.StageState.RUNNING, event.stage().state());
    }

    @Test
    void snapshotForSucceededTaskCarriesCompletedStageEvent() {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "demo.mp4", new WorkspaceKey("a1b2c3d4e5f6"), "dir");
        task.start();
        task.updateStage("translate");
        task.succeed();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        TaskProgressMessage event = service().snapshot(task.getTaskNo().value());

        assertEquals(TaskStatus.SUCCEEDED, event.taskStatus());
        assertEquals("translate", event.stage().stage());
        assertEquals(100, event.stage().progress());
        assertEquals(StageEvent.StageState.SUCCEEDED, event.stage().state());
    }

    @Test
    void snapshotForFailedTaskCarriesErrorMessageInStageEvent() {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "demo.mp4", new WorkspaceKey("a1b2c3d4e5f6"), "dir");
        task.start();
        task.updateStage("clean");
        task.fail("任务在 clean 阶段失败：boom");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        TaskProgressMessage event = service().snapshot(task.getTaskNo().value());

        assertEquals(TaskStatus.FAILED, event.taskStatus());
        assertEquals(StageEvent.StageState.FAILED, event.stage().state());
        assertEquals("任务在 clean 阶段失败：boom", event.stage().message(),
                "失败快照的消息携带终态错误（SSE 上唯一的错误出口）");
    }

    @Test
    void snapshotThrowsNotFoundWhenTaskMissing() {
        when(repository.findByTaskNo(new TaskNo("ghost"))).thenReturn(Optional.empty());

        assertThrows(TaskNotFoundException.class, () -> service().snapshot("ghost"));
    }

    // ---------- SSE 快照：翻译子状态覆盖（2026-10-02 两段式） ----------

    @Test
    void snapshotForSucceededTaskWithRunningTranslationCarriesTranslateStage() {
        VideoTask task = succeededWithTranslation(TranslationStatus.RUNNING, null);
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        TaskProgressMessage event = service().snapshot(task.getTaskNo().value());

        assertEquals(TaskStatus.SUCCEEDED, event.taskStatus(), "主状态不被翻译子状态改写");
        assertEquals(TranslationStatus.RUNNING, event.translationStatus(),
                "快照携带翻译子状态（订阅端终态判定据此保持连接）");
        assertEquals("translate", event.stage().stage());
        assertEquals(0, event.stage().progress(), "翻译进行中取粗粒度 0，精确进度由增量事件携带");
        assertEquals(StageEvent.StageState.RUNNING, event.stage().state());
    }

    @Test
    void snapshotForSucceededTaskWithSucceededTranslationCarriesCompletedTranslateStage() {
        VideoTask task = succeededWithTranslation(TranslationStatus.SUCCEEDED, null);
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        TaskProgressMessage event = service().snapshot(task.getTaskNo().value());

        assertEquals(TranslationStatus.SUCCEEDED, event.translationStatus());
        assertEquals("translate", event.stage().stage());
        assertEquals(100, event.stage().progress());
        assertEquals(StageEvent.StageState.SUCCEEDED, event.stage().state());
    }

    @Test
    void snapshotForSucceededTaskWithFailedTranslationCarriesErrorMessage() {
        VideoTask task = succeededWithTranslation(TranslationStatus.FAILED, "翻译失败：LLM 返回掉行");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        TaskProgressMessage event = service().snapshot(task.getTaskNo().value());

        assertEquals(TranslationStatus.FAILED, event.translationStatus());
        assertEquals(StageEvent.StageState.FAILED, event.stage().state());
        assertEquals("翻译失败：LLM 返回掉行", event.stage().message(),
                "翻译失败快照的消息携带失败原因（重连后仍可见，不只活在实时事件里）");
    }

    @Test
    void snapshotWithoutTranslationKeepsMainPipelineStageEvent() {
        // 主任务成功且翻译从未触发：快照仍是主管线形状（阶段取 current_stage 的粗粒度完成位）
        VideoTask task = VideoTask.newPending(TaskInputType.URL, "https://example.com/v",
                new WorkspaceKey("a1b2c3d4e5f6"), "dir");
        task.start();
        task.updateStage("clean");
        task.succeed();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        TaskProgressMessage event = service().snapshot(task.getTaskNo().value());

        assertNull(event.translationStatus(), "从未触发翻译 → 翻译子状态为 null");
        assertEquals("clean", event.stage().stage(), "不受翻译分支影响");
        assertEquals(100, event.stage().progress());
    }

    // ---------- 按需触发翻译（2026-10-02 两段式） ----------

    @Test
    void translateDelegatesToPipelineAndReturnsRefreshedView() {
        VideoTask before = succeededWithTranslation(null, null);
        // loadTask 读一次；translateTask（mock，模拟内部已推进翻译子状态）后重读一次
        when(repository.findByTaskNo(before.getTaskNo()))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(succeededWithTranslation(TranslationStatus.RUNNING, null)));

        TaskSnapshot view = service().translate(before.getTaskNo().value());

        verify(pipelineService).translateTask(before.getTaskNo());
        assertEquals(before.getTaskNo().value(), view.taskNo());
        assertEquals(TaskStatus.SUCCEEDED, view.status());
        assertEquals(TranslationStatus.RUNNING, view.translationStatus(),
                "视图应反映触发后的翻译子状态（重读而非触发前的陈旧副本）");
    }

    @Test
    void translateThrowsNotFoundWhenTaskMissing() {
        when(repository.findByTaskNo(new TaskNo("ghost"))).thenReturn(Optional.empty());

        assertThrows(TaskNotFoundException.class, () -> service().translate("ghost"));
        verify(pipelineService, never()).translateTask(any());
    }

    @Test
    void translateRejectsBlankTaskNo() {
        assertThrows(IllegalArgumentException.class, () -> service().translate(" "));
        verify(pipelineService, never()).translateTask(any());
    }

    // ---------- 产物下载（含路径穿越防护） ----------

    @Test
    void openArtifactReturnsFileInsideWorkspace() throws Exception {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "demo.mp4", new WorkspaceKey("a1b2c3d4e5f6"), "dir-artifact");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        Path dir = tempDir.resolve("works").resolve("dir-artifact");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("subtitles-cloud-translate-ja.srt"), "srt-content");
        byte[] expected = Files.readAllBytes(dir.resolve("subtitles-cloud-translate-ja.srt"));

        TaskArtifactFile artifact = service().openArtifact(task.getTaskNo().value(), "subtitles-cloud-translate-ja.srt");

        assertEquals("subtitles-cloud-translate-ja.srt", artifact.name());
        assertEquals(expected.length, artifact.sizeBytes());
        assertTrue(Files.isRegularFile(artifact.path()));
        assertEquals("srt-content", Files.readString(artifact.path()), "返回的应就是工作区内那份产物");
    }

    @Test
    void openArtifactRejectsDotDotTraversal() throws Exception {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "demo.mp4", new WorkspaceKey("a1b2c3d4e5f6"), "dir-traverse");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        Files.createDirectories(tempDir.resolve("works").resolve("dir-traverse"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().openArtifact(task.getTaskNo().value(), "../secret.txt"));

        assertTrue(e.getMessage().contains("非法产物路径"), e.getMessage());
    }

    @Test
    void openArtifactRejectsAbsoluteWindowsPath() throws Exception {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "demo.mp4", new WorkspaceKey("a1b2c3d4e5f6"), "dir-abs");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        Files.createDirectories(tempDir.resolve("works").resolve("dir-abs"));
        // 绝对路径入参 resolve 会整条替换基准目录，必须被 startsWith 校验拦下
        Path outside = Files.createTempFile("vidsage-escape", ".txt");
        try {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service().openArtifact(task.getTaskNo().value(), outside.toString()));
            assertTrue(e.getMessage().contains("非法产物路径"), e.getMessage());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void openArtifactRejectsBackslashTraversal() throws Exception {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "demo.mp4", new WorkspaceKey("a1b2c3d4e5f6"), "dir-backslash");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        Files.createDirectories(tempDir.resolve("works").resolve("dir-backslash"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().openArtifact(task.getTaskNo().value(), "..\\secret.txt"));

        assertTrue(e.getMessage().contains("非法产物路径"), e.getMessage());
    }

    @Test
    void openArtifactTreatsEncodedTraversalAsNonexistentArtifact() throws Exception {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "demo.mp4", new WorkspaceKey("a1b2c3d4e5f6"), "dir-encoded");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        Files.createDirectories(tempDir.resolve("works").resolve("dir-encoded"));

        // URL 编码产物在服务层只是普通文件名：不构成逃逸，落「产物不存在」分支
        for (String name : new String[] {"%2e%2e%2fsecret.txt", "%2e%2e/secret.txt"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service().openArtifact(task.getTaskNo().value(), name), () -> "name=" + name);
            assertTrue(e.getMessage().contains("产物不存在"), () -> "name=" + name + ", msg=" + e.getMessage());
        }
    }

    @Test
    void openArtifactRejectsMissingFile() throws Exception {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "demo.mp4", new WorkspaceKey("a1b2c3d4e5f6"), "dir-missing");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        Files.createDirectories(tempDir.resolve("works").resolve("dir-missing"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().openArtifact(task.getTaskNo().value(), "ghost.srt"));

        assertTrue(e.getMessage().contains("产物不存在"), e.getMessage());
    }

    @Test
    void openArtifactRejectsBlankFileName() {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "demo.mp4", new WorkspaceKey("a1b2c3d4e5f6"), "dir");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        assertThrows(IllegalArgumentException.class, () -> service().openArtifact(task.getTaskNo().value(), " "));
    }

    @Test
    void openArtifactThrowsNotFoundWhenTaskMissing() {
        when(repository.findByTaskNo(new TaskNo("ghost"))).thenReturn(Optional.empty());

        assertThrows(TaskNotFoundException.class,
                () -> service().openArtifact("ghost", "whatever.srt"));
    }

    // ---------- 工具 ----------

    private static String sha256Prefix(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 12);
    }

    /**
     * 主任务已成功、翻译子状态随给定值构造的任务（errorMessage 由调用方给，模拟翻译失败落库的形状）。
     */
    private static VideoTask succeededWithTranslation(TranslationStatus translationStatus, String errorMessage) {
        return VideoTask.restore(7L, new TaskNo("abc123def456abc123def456abc123de"), TaskInputType.URL,
                "https://example.com/v", new WorkspaceKey("a1b2c3d4e5f6"), "dir", TaskStatus.SUCCEEDED, "clean",
                translationStatus, errorMessage,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now(),
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
    }
}
