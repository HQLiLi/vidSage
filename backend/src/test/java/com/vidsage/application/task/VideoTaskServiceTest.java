package com.vidsage.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.task.message.TaskProgressMessage;
import com.vidsage.application.task.notify.TaskProgressNotifier;
import com.vidsage.application.task.subscribe.TaskSubscriptionRegistry;
import com.vidsage.infrastructure.fs.NioWorkspaceDirectoryManager;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.application.task.pipeline.SubtitlePipeline;
import com.vidsage.application.task.query.TaskArtifactFile;
import com.vidsage.application.task.query.TaskSnapshot;
import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.shared.model.WorkspaceDir;
import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.port.SubtitleKeywordRepository;
import com.vidsage.domain.task.model.TaskInputType;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;
import com.vidsage.domain.task.port.VideoTaskRepository;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import com.vidsage.application.task.session.TaskSessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * {@link VideoTaskService} 的纯单元测试：仓储与管线用 Mockito mock，工作区用真实实例 + 临时目录
 * （目录公式单源在 domain/shared，{@code NioWorkspaceDirectoryManager} 真实落盘——真跑比 mock 更能钉住路径约定）。
 *
 * <p>覆盖「URL 路线落库与启动管线」「上传路线流式落盘按内容寻址」「查重 409 与孤儿文件清理」「查询」。
 */
class VideoTaskServiceTest {

    private static final String SOURCE_LANG = "zh-Hans";

    private static final String TARGET_LANG = "zh-Hant";

    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private final VideoTaskRepository repository = org.mockito.Mockito.mock(VideoTaskRepository.class);
    private final SubtitlePipeline pipeline = org.mockito.Mockito.mock(SubtitlePipeline.class);

    private final SubtitleKeywordRepository keywordRepository =
            org.mockito.Mockito.mock(SubtitleKeywordRepository.class);
    private final TaskSessionRegistry sessionRegistry = new TaskSessionRegistry();

    /**
     * 工作区用真实实例（目录公式单源在 domain/shared，workspaceFor 真实落盘，真跑比 mock
     * 更能钉住路径约定）。{@code @TempDir} 在字段初始化之后才注入，故只能方法内构造，
     * 不能在字段初始化器里引用。
     */
    private VideoTaskService service() {
        // 第 5 参是真 notifier（repository mock + 新 registry）：快照组装走 notifier 与在线事件同源
        return new VideoTaskService(repository, keywordRepository, pipeline,
                new NioWorkspaceDirectoryManager(new WorkspaceProperties(tempDir.resolve("works").toString())),
                // notifier 与 service 共用同一个登记表：生产里它们也是同一个 bean，
                // 分开建会让「在途会话 → 快照渲染成 RUNNING」这条链在本测试里断掉
                new TaskProgressNotifier(repository, new TaskSubscriptionRegistry(), sessionRegistry),
                sessionRegistry);
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

        TaskSnapshot view = service().submitUrl("https://example.com/watch/1", null);

        ArgumentCaptor<VideoTask> captor = ArgumentCaptor.forClass(VideoTask.class);
        verify(repository).insert(captor.capture());
        VideoTask inserted = captor.getValue();
        assertEquals(TaskInputType.URL, inserted.getInputType());
        assertEquals("https://example.com/watch/1", inserted.getSourceUri());
        assertEquals(TaskStatus.PENDING, inserted.getStatus());
        assertTrue(inserted.getTaskNo().value().matches("[0-9a-f]{32}"));
        // 目录名与 WorkspaceDir 同公式：sanitize(url)-hash12（非法字符替换为下划线）
        WorkspaceKey expectedKey = new com.vidsage.domain.media.model.MediaInput.RemoteUrl("https://example.com/watch/1")
                .getContentKey(com.vidsage.domain.media.model.MediaFileInfo.NOT_APPLICABLE);
        String expectedDir = WorkspaceDir.sanitizeName("https://example.com/watch/1") + "-" + expectedKey.value();
        assertEquals(expectedDir, inserted.getWorkspaceDir());

        verify(pipeline).execute(inserted.getTaskNo());
        assertEquals(inserted.getTaskNo().value(), view.taskNo());
        assertEquals(TaskStatus.PENDING, view.status());
        assertEquals(TaskInputType.URL, view.inputType());
    }

    @Test
    void submitUrlRejectsBlankUrlWithoutTouchingRepository() {
        assertThrows(IllegalArgumentException.class, () -> service().submitUrl("   ", null));

        verify(repository, never()).insert(any());
        verify(pipeline, never()).execute(any());
    }

    @Test
    void submitUrlConflictsWithActiveTaskAndCarriesExistingView() {
        VideoTask existing = VideoTask.newPending(TaskInputType.URL, "https://example.com/watch/1",
                new WorkspaceKey("a1b2c3d4e5f6"), "dir");
        when(repository.findActiveByWorkspaceKey(any())).thenReturn(Optional.of(existing));

        TaskConflictException e = assertThrows(TaskConflictException.class,
                () -> service().submitUrl("https://example.com/watch/1", null));

        assertEquals(existing.getTaskNo().value(), e.getExistingTask().taskNo());
        assertEquals(TaskStatus.PENDING, e.getExistingTask().status());
        assertTrue(e.getMessage().contains("请勿重复提交"));
        verify(repository, never()).insert(any());
        verify(pipeline, never()).execute(any());
    }

    // ---------- 上传路线 ----------

    @Test
    void submitUploadStreamsToContentAddressedWorkspace() throws Exception {
        when(repository.findActiveByWorkspaceKey(any())).thenReturn(Optional.empty());
        byte[] media = "fake-video-bytes-0123456789".getBytes(StandardCharsets.UTF_8);
        String expectedKey = sha256Prefix(media);

        TaskSnapshot view = service().submitUpload(new ByteArrayInputStream(media), "我的 视频.mp4", null);

        Path dir = tempDir.resolve("works").resolve("我的 视频.mp4-" + expectedKey);
        Path source = dir.resolve("source.mp4");
        assertTrue(Files.isDirectory(dir), () -> "应按 sanitize(原名)-<内容 key> 建工作区：" + dir);
        assertTrue(Files.isRegularFile(source), "归位后应有 source.mp4");
        // 边写边算：文件内容与上传流完全相同
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(media)),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));

        ArgumentCaptor<VideoTask> captor = ArgumentCaptor.forClass(VideoTask.class);
        verify(repository).insert(captor.capture());
        VideoTask inserted = captor.getValue();
        assertEquals(TaskInputType.UPLOAD, inserted.getInputType());
        assertEquals("我的 视频.mp4", inserted.getSourceUri());
        assertEquals(expectedKey, inserted.getWorkspaceKey().value());
        assertEquals("我的 视频.mp4-" + expectedKey, inserted.getWorkspaceDir());
        verify(pipeline).execute(inserted.getTaskNo());
        assertEquals(inserted.getTaskNo().value(), view.taskNo());
    }

    @Test
    void submitUploadReusesSameDirectoryForSameContentAndFallsBackLabelForBlankName() throws Exception {
        when(repository.findActiveByWorkspaceKey(any())).thenReturn(Optional.empty());
        byte[] media = "same-content".getBytes(StandardCharsets.UTF_8);

        service().submitUpload(new ByteArrayInputStream(media), "  ", null);
        service().submitUpload(new ByteArrayInputStream(media), "  ", null);

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
                () -> service().submitUpload(new ByteArrayInputStream(media), "a.mp4", null));

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
        assertThrows(IllegalArgumentException.class, () -> service().submitUpload(null, "a.mp4", null));
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
        task.updateStage(SubtitleStageName.TRANSCRIBE_AUDIO.getStageName());
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        TaskProgressMessage event = service().snapshot(task.getTaskNo().value());

        assertEquals(TaskStatus.RUNNING, event.taskStatus());
        assertEquals(SubtitleStageName.TRANSCRIBE_AUDIO.getStageName(), event.stage().stage());
        assertEquals(0, event.stage().progress(), "快照只保证形状合法，精确进度由增量事件携带");
        assertEquals(StageProgress.State.RUNNING, event.stage().state());
    }

    @Test
    void snapshotForSucceededTaskCarriesCompletedStageEvent() {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "demo.mp4", new WorkspaceKey("a1b2c3d4e5f6"), "dir");
        task.start();
        task.updateStage(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName());
        task.succeed();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        TaskProgressMessage event = service().snapshot(task.getTaskNo().value());

        assertEquals(TaskStatus.SUCCEEDED, event.taskStatus());
        assertEquals(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), event.stage().stage());
        assertEquals(100, event.stage().progress());
        assertEquals(StageProgress.State.SUCCEEDED, event.stage().state());
    }

    @Test
    void snapshotForFailedTaskCarriesErrorMessageInStageEvent() {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "demo.mp4", new WorkspaceKey("a1b2c3d4e5f6"), "dir");
        task.start();
        task.updateStage(SubtitleStageName.CLEAN_SUBTITLES.getStageName());
        task.fail("任务执行失败：boom");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        TaskProgressMessage event = service().snapshot(task.getTaskNo().value());

        assertEquals(TaskStatus.FAILED, event.taskStatus());
        assertEquals(StageProgress.State.FAILED, event.stage().state());
        assertEquals("任务执行失败：boom", event.stage().message(),
                "失败快照的消息携带终态错误（SSE 上唯一的错误出口）");
    }

    @Test
    void snapshotThrowsNotFoundWhenTaskMissing() {
        when(repository.findByTaskNo(new TaskNo("ghost"))).thenReturn(Optional.empty());

        assertThrows(TaskNotFoundException.class, () -> service().snapshot("ghost"));
    }

    // ---------- SSE 快照：在途后置会话 ----------

    @Test
    void snapshotRendersInFlightTranslateSessionAsRunning() {
        // 翻译子状态列已下线：「翻译在跑」这件事只有登记表回答得了（AC9 的机制）
        VideoTask task = succeededTask();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        assertTrue(sessionRegistry.tryStart(task.getWorkspaceKey(),
                SubtitleStageName.TRANSLATE_SUBTITLES.getStageName()));

        TaskProgressMessage event = service().snapshot(task.getTaskNo().value());

        assertEquals(TaskStatus.SUCCEEDED, event.taskStatus(), "主状态不被在途会话改写");
        assertEquals(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), event.stage().stage());
        assertEquals(0, event.stage().progress(), "在途会话取粗粒度 0，精确进度由增量事件携带");
        assertEquals(StageProgress.State.RUNNING, event.stage().state());
        assertFalse(event.terminal(), "在途会话 → 未收敛：重连的人不会一连上就被收口");
    }

    @Test
    void snapshotWithoutInFlightSessionKeepsMainPipelineStageEvent() {
        // 没有在途会话：快照是主管线形状（阶段取 current_stage 的粗粒度完成位）
        VideoTask task = VideoTask.newPending(TaskInputType.URL, "https://example.com/v",
                new WorkspaceKey("a1b2c3d4e5f6"), "dir");
        task.start();
        task.updateStage(SubtitleStageName.CLEAN_SUBTITLES.getStageName());
        task.succeed();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        TaskProgressMessage event = service().snapshot(task.getTaskNo().value());

        assertEquals(SubtitleStageName.CLEAN_SUBTITLES.getStageName(), event.stage().stage());
        assertEquals(100, event.stage().progress());
        assertTrue(event.terminal(), "无在途会话 → 主状态终态即收敛");
    }

    // ---------- 按需触发翻译 ----------

    @Test
    void translateStartsSessionAndReturnsView() {
        VideoTask task = succeededTask();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        TaskSnapshot view = service().translate(task.getTaskNo().value(), SOURCE_LANG, TARGET_LANG);

        verify(pipeline).translateTask(task, SupportedLanguage.SIMPLE_CHINESE, SupportedLanguage.TRADITIONAL_CHINESE);
        assertEquals(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(),
                sessionRegistry.findRunningStage(task.getWorkspaceKey()),
                "同步段抢到登记：202 返回前它就写好了（「订阅必须先于 POST」由此不再需要）");
        assertEquals(task.getTaskNo().value(), view.taskNo());
        assertEquals(TaskStatus.SUCCEEDED, view.status());
        // 本用例把管线 mock 掉了，异步体的 finally 不会跑——手工退登记，避免用例之间串味
        sessionRegistry.finish(task.getWorkspaceKey());
    }

    @Test
    void translateRejectsWhenAnotherPostSessionIsRunningOnSameWorkspace() {
        VideoTask task = succeededTask();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        assertTrue(sessionRegistry.tryStart(task.getWorkspaceKey(),
                SubtitleStageName.CLEAN_SUBTITLES.getStageName()), "先占住登记，模拟重新生成在跑");

        TaskConflictException e = assertThrows(TaskConflictException.class,
                () -> service().translate(task.getTaskNo().value(), SOURCE_LANG, TARGET_LANG));

        assertEquals(task.getTaskNo().value(), e.getExistingTask().taskNo(),
                "409 必须附既有任务快照——冲突的出路是去订阅它（AC10）");
        verify(pipeline, never()).translateTask(any(), any(), any());
    }

    @Test
    void translateReleasesRegistrationWhenPipelineThrowsInSyncSegment() {
        VideoTask task = succeededTask();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        doThrow(new IllegalStateException("模拟同步段抛出"))
                .when(pipeline).translateTask(any(), any(), any());

        assertThrows(IllegalStateException.class,
                () -> service().translate(task.getTaskNo().value(), SOURCE_LANG, TARGET_LANG));

        assertNull(sessionRegistry.findRunningStage(task.getWorkspaceKey()),
                "同步段抛出必须退登记，否则该工作区的互斥再也释放不掉（幽灵）");
    }

    @Test
    void translateRejectsWhenMainStateNotSucceededAndLeavesNoRegistration() {
        VideoTask task = VideoTask.newPending(TaskInputType.URL, "https://example.com/v",
                new WorkspaceKey("a1b2c3d4e5f6"), "dir");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        assertThrows(IllegalArgumentException.class,
                () -> service().translate(task.getTaskNo().value(), SOURCE_LANG, TARGET_LANG));

        verify(pipeline, never()).translateTask(any(), any(), any());
        assertNull(sessionRegistry.findRunningStage(task.getWorkspaceKey()),
                "400 早于抢登记：主状态没就绪不该占住锁");
    }

    @Test
    void translateThrowsNotFoundWhenTaskMissing() {
        when(repository.findByTaskNo(new TaskNo("ghost"))).thenReturn(Optional.empty());

        assertThrows(TaskNotFoundException.class, () -> service().translate("ghost", SOURCE_LANG, TARGET_LANG));
        verify(pipeline, never()).translateTask(any(), any(), any());
    }

    @Test
    void translateRejectsBlankTaskNo() {
        assertThrows(IllegalArgumentException.class, () -> service().translate(" ", SOURCE_LANG, TARGET_LANG));
        verify(pipeline, never()).translateTask(any(), any(), any());
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
    // ---------- 重新生成字幕（后置会话同步段） ----------

    @Test
    void regenerateWithKeywordsReplacesStoreAndDelegates() {
        VideoTask task = succeededTask();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        service().regenerate(task.getTaskNo().value(), List.of("影视飓风"));

        ArgumentCaptor<SubtitleKeywords> captor = ArgumentCaptor.forClass(SubtitleKeywords.class);
        verify(keywordRepository).replace(eq(task.getWorkspaceKey()), captor.capture());
        assertEquals(List.of("影视飓风"), captor.getValue().values());
        verify(pipeline).regenerateSubtitles(eq(task), captor.capture());
    }

    @Test
    void regenerateWithNullKeywordsKeepsExistingList() {
        VideoTask task = succeededTask();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        when(keywordRepository.findByWorkspaceKey(task.getWorkspaceKey()))
                .thenReturn(new SubtitleKeywords(List.of("旧词")));

        service().regenerate(task.getTaskNo().value(), null);

        verify(keywordRepository, never()).replace(any(), any());
        ArgumentCaptor<SubtitleKeywords> captor = ArgumentCaptor.forClass(SubtitleKeywords.class);
        verify(pipeline).regenerateSubtitles(eq(task), captor.capture());
        assertEquals(List.of("旧词"), captor.getValue().values(), "null = 不动已有列表，按库里那份重跑");
    }

    @Test
    void regenerateRejectsNotReadyTaskWithoutTouchingRegistry() {
        VideoTask pending = VideoTask.newPending(TaskInputType.URL, "https://example.com/v",
                new WorkspaceKey("a1b2c3d4e5f6"), "dir");
        when(repository.findByTaskNo(pending.getTaskNo())).thenReturn(Optional.of(pending));

        assertThrows(IllegalArgumentException.class,
                () -> service().regenerate(pending.getTaskNo().value(), null));

        verify(pipeline, never()).regenerateSubtitles(any(), any());
        assertNull(sessionRegistry.findRunningStage(pending.getWorkspaceKey()),
                "未就绪不该抢登记——抢了不退就是幽灵（AC10 的同步段这一半）");
    }

    @Test
    void regenerateRejectsOverLimitBeforeTouchingAnything() {
        VideoTask task = succeededTask();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add("词" + i);
        }

        assertThrows(IllegalArgumentException.class,
                () -> service().regenerate(task.getTaskNo().value(), tooMany));

        verify(keywordRepository, never()).replace(any(), any());
        verify(pipeline, never()).regenerateSubtitles(any(), any());
        assertNull(sessionRegistry.findRunningStage(task.getWorkspaceKey()), "超限连登记都不该抢（AC8）");
    }

    @Test
    void regenerateConflictReturns409WithoutTouchingKeywords() {
        VideoTask task = succeededTask();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        assertTrue(sessionRegistry.tryStart(task.getWorkspaceKey(), SubtitleStageName.CLEAN_SUBTITLES.getStageName()),
                "先占住模拟「已有会话在跑」");

        assertThrows(TaskConflictException.class,
                () -> service().regenerate(task.getTaskNo().value(), List.of("新词")));

        verify(keywordRepository, never()).replace(any(), any());
        verify(pipeline, never()).regenerateSubtitles(any(), any());
    }

    @Test
    void regenerateReleasesRegistryWhenHandoffFails() {
        // AC10 的同步段这一半：抢到登记之后、委派出去之前任何一步抛了，登记必须在 finally 退掉——
        // 漏了该工作区的互斥就再也释放不掉（比卡 loading 更严重）
        VideoTask task = succeededTask();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        doThrow(new IllegalStateException("模拟关键词落库失败：库不可达"))
                .when(keywordRepository).replace(any(), any());

        assertThrows(IllegalStateException.class,
                () -> service().regenerate(task.getTaskNo().value(), List.of("影视飓风")));

        assertNull(sessionRegistry.findRunningStage(task.getWorkspaceKey()),
                "同步段异常必须退登记，否则留下永远在跑的幽灵");
        verify(pipeline, never()).regenerateSubtitles(any(), any());
        // 紧接着再触发一次必须能抢到登记（AC9 的下半句）
        assertTrue(sessionRegistry.tryStart(task.getWorkspaceKey(),
                SubtitleStageName.CLEAN_SUBTITLES.getStageName()));
    }

    // ---------- 关键词查询 ----------

    @Test
    void keywordsReturnsStoreContentsAndFingerprint() {
        VideoTask task = succeededTask();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        SubtitleKeywords stored = new SubtitleKeywords(List.of("影视飓风"));
        when(keywordRepository.findByWorkspaceKey(task.getWorkspaceKey())).thenReturn(stored);

        var snapshot = service().findKeywords(task.getTaskNo().value());

        assertEquals(List.of("影视飓风"), snapshot.values());
        assertEquals(stored.computeFingerprint(), snapshot.fingerprint(),
                "指纹必须与产物里那一栏同算法（单源在 SubtitleKeywords）");
    }


    /** 主任务已成功、停在清洗阶段的既有任务——后置会话用例的起点。 */
    private static VideoTask succeededTask() {
        return VideoTask.restore(7L, new TaskNo("abc123def456abc123def456abc123de"), TaskInputType.URL,
                "https://example.com/v", new WorkspaceKey("a1b2c3d4e5f6"), "dir", TaskStatus.SUCCEEDED,
                SubtitleStageName.CLEAN_SUBTITLES.getStageName(), null,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now(),
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
    }
}
