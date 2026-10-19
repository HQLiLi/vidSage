package com.vidsage.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.application.subtitle.SubtitleArtifactStore;
import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.subtitle.pipeline.SubtitlePipeline;
import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.task.model.TaskRunStatus;
import com.vidsage.domain.task.model.TaskRunType;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;
import com.vidsage.domain.subtitle.port.SubtitleKeywordRepository;
import com.vidsage.domain.task.port.SubtitleTaskRepository;
import com.vidsage.domain.task.port.TaskRunRepository;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.domain.workspace.model.ResolvedMediaIdentity;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import com.vidsage.domain.workspace.port.MediaIdentityResolver;
import com.vidsage.domain.workspace.port.WorkspaceRepository;
import com.vidsage.infrastructure.fs.NioWorkspaceDirectoryManager;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * {@link SubtitleTaskService} 的纯单元测试：仓储、身份解析、收口机制类、管线全部 mock，
 * 事务管理器用「就地跑回调」的桩，工作区管理器用真实实例 + 临时目录。
 *
 * <p>重点覆盖提交路径的四条纪律：身份键在事务外算、两行撞键走锁定读重查、409 带既有 runNo、
 * 「未就绪」是 400 不是 409；以及三条触发路径各自派发到管线的对应执行体。
 */
class SubtitleTaskServiceTest {

    @TempDir
    Path tempDir;

    private final WorkspaceRepository workspaceRepository = Mockito.mock(WorkspaceRepository.class);
    private final SubtitleTaskRepository subtitleTaskRepository = Mockito.mock(SubtitleTaskRepository.class);
    private final TaskRunRepository taskRunRepository = Mockito.mock(TaskRunRepository.class);
    private final SubtitleKeywordRepository keywordRepository = Mockito.mock(SubtitleKeywordRepository.class);
    private final MediaIdentityResolver mediaIdentityResolver = Mockito.mock(MediaIdentityResolver.class);
    private final TaskRunSupervisor supervisor = Mockito.mock(TaskRunSupervisor.class);
    private final SubtitlePipeline pipeline = Mockito.mock(SubtitlePipeline.class);
    private final SubtitleArtifactStore artifactStore = Mockito.mock(SubtitleArtifactStore.class);

    private static final long TASK_ID = 1L;
    private static final String TASK_NO = "3f2a9c1e3f2a9c1e3f2a9c1e3f2a9c1e";
    private static final String URL = "https://www.bilibili.com/video/BV11QMd6WEJp";
    private static final String SOURCE_KEY = "BV11QMd6WEJp|1";

    private SubtitleTaskService service() {
        return service(inlineTransactionManager());
    }

    private SubtitleTaskService service(PlatformTransactionManager transactionManager) {
        return new SubtitleTaskService(workspaceRepository, subtitleTaskRepository, taskRunRepository,
                keywordRepository, mediaIdentityResolver, supervisor, pipeline, directoryManager(),
                new SubtitleTaskFinder(subtitleTaskRepository, workspaceRepository, directoryManager()),
                artifactStore, transactionManager);
    }

    private NioWorkspaceDirectoryManager directoryManager() {
        return new NioWorkspaceDirectoryManager(new WorkspaceProperties(tempDir.resolve("works").toString()));
    }

    // ---------- 提交路径：URL ----------

    @Test
    void submitUrlCreatesIdentityAndDispatchesMainRun() {
        Workspace workspace = urlWorkspace();
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        stubUrlSubmission(workspace, run);

        TaskRunRef ref = service().submitUrl(URL, null);

        assertEquals(TASK_NO, ref.taskNo());
        assertEquals(1, ref.runNo());
        verify(supervisor).start(any(SubtitleTask.class), eq(TaskRunType.MAIN), isNull());
        verify(pipeline).run(eq(run), any(), eq(workspace));
    }

    @Test
    void secondSubmitInsertsNewRunInsteadOfConflicting() {
        Workspace workspace = urlWorkspace();
        TaskRun secondRun = runningRun(TaskRunType.MAIN, null, 2);
        when(mediaIdentityResolver.resolve(anyString())).thenReturn(identity());
        when(workspaceRepository.findBySourceTypeAndSourceKey(any(), any())).thenReturn(Optional.of(workspace));
        when(subtitleTaskRepository.findByWorkspaceId(TASK_ID)).thenReturn(Optional.of(task()));
        when(supervisor.start(any(), eq(TaskRunType.MAIN), isNull())).thenReturn(Optional.of(secondRun));

        TaskRunRef ref = service().submitUrl(URL, null);

        assertEquals(2, ref.runNo(), "同一 URL 二次提交是插新 run，不是 409");
        verify(workspaceRepository, never()).insert(any());
        verify(subtitleTaskRepository, never()).insert(any());
    }

    @Test
    void conflictWhenTaskAlreadyHasRunningRun() {
        Workspace workspace = urlWorkspace();
        TaskRun existing = runningRun(TaskRunType.MAIN, null, 3);
        when(mediaIdentityResolver.resolve(anyString())).thenReturn(identity());
        when(workspaceRepository.findBySourceTypeAndSourceKey(any(), any())).thenReturn(Optional.of(workspace));
        when(subtitleTaskRepository.findByWorkspaceId(TASK_ID)).thenReturn(Optional.of(task()));
        when(supervisor.start(any(), eq(TaskRunType.MAIN), isNull())).thenReturn(Optional.empty());
        when(taskRunRepository.findLatestForShareByTaskId(TASK_ID)).thenReturn(Optional.of(existing));

        TaskConflictException e = assertThrows(TaskConflictException.class,
                () -> service().submitUrl(URL, null));

        assertEquals(TASK_NO, e.getTaskNo());
        assertEquals(3, e.getRunNo(), "409 要带既有 run 的 runNo");
        verify(pipeline, never()).run(any(), any(), any());
    }

    @Test
    void concurrentDuplicateSourceFallsBackToLockedRead() {
        Workspace workspace = urlWorkspace();
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        when(mediaIdentityResolver.resolve(anyString())).thenReturn(identity());
        when(workspaceRepository.findBySourceTypeAndSourceKey(any(), any())).thenReturn(Optional.empty());
        when(workspaceRepository.insert(any())).thenThrow(new DuplicateKeyException("撞 uk_source"));
        when(workspaceRepository.findForShareBySourceTypeAndSourceKey(any(), any()))
                .thenReturn(Optional.of(workspace));
        when(subtitleTaskRepository.findByWorkspaceId(TASK_ID)).thenReturn(Optional.empty());
        when(subtitleTaskRepository.insert(any())).thenThrow(new DuplicateKeyException("撞 uk_workspace"));
        when(subtitleTaskRepository.findForShareByWorkspaceId(TASK_ID)).thenReturn(Optional.of(task()));
        when(supervisor.start(any(), eq(TaskRunType.MAIN), isNull())).thenReturn(Optional.of(run));

        TaskRunRef ref = service().submitUrl(URL, null);

        assertEquals(TASK_NO, ref.taskNo(), "并发撞键收敛到同一条任务，不报 409");
        verify(workspaceRepository).findForShareBySourceTypeAndSourceKey(any(), any());
        verify(subtitleTaskRepository).findForShareByWorkspaceId(TASK_ID);
    }

    @Test
    void identityIsResolvedBeforeTheTransactionOpens() {
        Workspace workspace = urlWorkspace();
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        List<String> order = new ArrayList<>();
        when(mediaIdentityResolver.resolve(anyString())).thenAnswer(invocation -> {
            order.add("resolve");
            return identity();
        });
        when(workspaceRepository.findBySourceTypeAndSourceKey(any(), any())).thenReturn(Optional.of(workspace));
        when(subtitleTaskRepository.findByWorkspaceId(TASK_ID)).thenReturn(Optional.of(task()));
        when(supervisor.start(any(), eq(TaskRunType.MAIN), isNull())).thenReturn(Optional.of(run));
        PlatformTransactionManager recording = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                order.add("begin");
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };

        service(recording).submitUrl(URL, null);

        assertEquals(List.of("resolve", "begin"), order, "身份键要走网络，必须在事务外先算完");
    }

    @Test
    void keywordsWrittenOnlyWhenRequestCarriesThem() {
        Workspace workspace = urlWorkspace();
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        stubUrlSubmission(workspace, run);
        SubtitleKeywords keywords = new SubtitleKeywords(List.of("影视飓风"));

        service().submitUrl(URL, List.of("影视飓风"));
        verify(keywordRepository).replace(TASK_ID, keywords);

        Mockito.reset(keywordRepository);
        service().submitUrl(URL, null);
        verify(keywordRepository, never()).replace(anyLong(), any());
    }

    @Test
    void blankUrlIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service().submitUrl("  ", null));
    }

    // ---------- 提交路径：上传 ----------

    @Test
    void submitUploadUsesFullSha256AsIdentityAndSettlesFile() throws Exception {
        byte[] media = "hello world".getBytes(StandardCharsets.UTF_8);
        String expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(media));
        // 落库后的身份带回内部 id（真实 insert 会回填）；测试用 restore 模拟，否则后续按 id 查任务会 NPE
        LocalDateTime now = LocalDateTime.now();
        Workspace uploaded = Workspace.restore(TASK_ID, SourceType.UPLOAD, new SourceKey(expectedSha),
                "demo.mp4", null, null, null, now, now);
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        when(workspaceRepository.findBySourceTypeAndSourceKey(any(), any())).thenReturn(Optional.empty());
        when(workspaceRepository.insert(any())).thenReturn(uploaded);
        when(subtitleTaskRepository.findByWorkspaceId(TASK_ID)).thenReturn(Optional.empty());
        when(subtitleTaskRepository.insert(any())).thenReturn(task());
        when(supervisor.start(any(), eq(TaskRunType.MAIN), isNull())).thenReturn(Optional.of(run));

        service().submitUpload(new ByteArrayInputStream(media), "我的 视频.mp4", null);

        ArgumentCaptor<Workspace> captor = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).insert(captor.capture());
        assertEquals(expectedSha, captor.getValue().getSourceKey().value(),
                "上传路线身份键是内容 sha256 全 64 位");
        assertEquals(64, captor.getValue().getSourceKey().value().length());
        assertTrue(Files.isRegularFile(tempDir.resolve("works").resolve(expectedSha).resolve("source.mp4")),
                "上传文件按身份键归位到工作区");
    }

    @Test
    void submitUploadRejectsNullContent() {
        InputStream content = null;

        assertThrows(IllegalArgumentException.class,
                () -> service().submitUpload(content, "demo.mp4", null));
    }

    // ---------- 重新生成 / 翻译 ----------

    @Test
    void regenerateRejectsWhenNoReadableOriginal() {
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.of(task()));
        when(workspaceRepository.findById(TASK_ID)).thenReturn(Optional.of(urlWorkspace()));
        when(artifactStore.findReadableReady(eq(TASK_ID), eq(ArtifactType.ORIGINAL), eq(""), any()))
                .thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().regenerate(TASK_NO, null),
                "没有可读的原始产物 → 400，不是 409");

        assertTrue(e.getMessage().contains("原始转写产物"));
        verify(supervisor, never()).start(any(), any(), any());
    }

    @Test
    void regenerateDispatchesRecleanRunWhenReadableOriginalExists() {
        TaskRun run = runningRun(TaskRunType.RECLEAN, null, 2);
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.of(task()));
        when(workspaceRepository.findById(TASK_ID)).thenReturn(Optional.of(urlWorkspace()));
        when(artifactStore.findReadableReady(eq(TASK_ID), eq(ArtifactType.ORIGINAL), eq(""), any()))
                .thenReturn(Optional.of(readyArtifact(ArtifactType.ORIGINAL)));
        when(supervisor.start(any(), eq(TaskRunType.RECLEAN), isNull())).thenReturn(Optional.of(run));
        when(keywordRepository.findByTaskId(TASK_ID)).thenReturn(new SubtitleKeywords(List.of()));

        TaskRunRef ref = service().regenerate(TASK_NO, List.of("影视飓风"));

        assertEquals(2, ref.runNo());
        verify(keywordRepository).replace(TASK_ID, new SubtitleKeywords(List.of("影视飓风")));
        verify(pipeline).regenerate(eq(run), any(), any(), any(SubtitleKeywords.class));
    }

    @Test
    void translateRejectsWhenNoReadableCleaned() {
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.of(task()));
        when(workspaceRepository.findById(TASK_ID)).thenReturn(Optional.of(urlWorkspace()));
        when(artifactStore.findReadableReady(eq(TASK_ID), eq(ArtifactType.CLEANED), eq(""), any()))
                .thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().translate(TASK_NO, "ja", "zh-Hans"),
                "没有可读的清洗产物 → 400，不是 409");

        assertTrue(e.getMessage().contains("清洗后的字幕产物"));
        verify(supervisor, never()).start(any(), any(), any());
    }

    @Test
    void translateDispatchesTranslateRunWhenReadableCleanedExists() {
        TaskRun run = runningRun(TaskRunType.TRANSLATE, "zh-Hans", 3);
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.of(task()));
        when(workspaceRepository.findById(TASK_ID)).thenReturn(Optional.of(urlWorkspace()));
        when(artifactStore.findReadableReady(eq(TASK_ID), eq(ArtifactType.CLEANED), eq(""), any()))
                .thenReturn(Optional.of(readyArtifact(ArtifactType.CLEANED)));
        when(supervisor.start(any(), eq(TaskRunType.TRANSLATE), any())).thenReturn(Optional.of(run));

        TaskRunRef ref = service().translate(TASK_NO, "ja", "zh-Hans");

        assertEquals(3, ref.runNo());
        verify(pipeline).translate(eq(run), any(), any(), any(SupportedLanguage.class),
                any(SupportedLanguage.class));
    }

    // ---------- 夹具 ----------

    private void stubUrlSubmission(Workspace workspace, TaskRun run) {
        when(mediaIdentityResolver.resolve(anyString())).thenReturn(identity());
        when(workspaceRepository.findBySourceTypeAndSourceKey(any(), any())).thenReturn(Optional.empty());
        when(workspaceRepository.insert(any())).thenReturn(workspace);
        when(subtitleTaskRepository.findByWorkspaceId(TASK_ID)).thenReturn(Optional.empty());
        when(subtitleTaskRepository.insert(any())).thenReturn(task());
        when(supervisor.start(any(), eq(TaskRunType.MAIN), isNull())).thenReturn(Optional.of(run));
    }

    private static SubtitleArtifact readyArtifact(ArtifactType type) {
        return switch (type) {
            case ORIGINAL -> SubtitleArtifact.restore(1L, TASK_ID, 1L, type, "", 1,
                    SubtitleArtifactStatus.READY, null, null, "original-segments.json",
                    "original-subtitles.srt", null, null, "a", "b", null, null, LocalDateTime.now());
            case CLEANED -> SubtitleArtifact.restore(2L, TASK_ID, 1L, type, "", 1,
                    SubtitleArtifactStatus.READY, null, "fp-1", "clean-segments.json",
                    "clean-subtitles.srt", "context.json", null, "a", "b", "c", null, LocalDateTime.now());
            case TRANSLATED -> SubtitleArtifact.restore(3L, TASK_ID, 1L, type, "zh-Hans", 1,
                    SubtitleArtifactStatus.READY, null, null, "translated-segments-zh-hans.json",
                    "translated-subtitles-zh-hans.srt", null, "terms-zh-hans.json", "a", "b", null, "c",
                    LocalDateTime.now());
        };
    }

    private static ResolvedMediaIdentity identity() {
        return new ResolvedMediaIdentity(new SourceKey(SOURCE_KEY), URL);
    }

    private static Workspace urlWorkspace() {
        LocalDateTime now = LocalDateTime.now();
        return Workspace.restore(TASK_ID, SourceType.URL, new SourceKey(SOURCE_KEY), URL, URL, null, null, now, now);
    }

    private static SubtitleTask task() {
        LocalDateTime now = LocalDateTime.now();
        return SubtitleTask.restore(TASK_ID, new TaskNo(TASK_NO), TASK_ID, now, now);
    }

    private static TaskRun runningRun(TaskRunType type, String targetLanguage, int runNo) {
        LocalDateTime now = LocalDateTime.now();
        return TaskRun.restore(1L, TASK_ID, runNo, type, targetLanguage, TaskRunStatus.RUNNING, null, null,
                now, null, now);
    }

    /** 事务桩：就地跑回调，不连真库，也不改事务语义。 */
    private static PlatformTransactionManager inlineTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
