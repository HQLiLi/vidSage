package com.vidsage.application.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vidsage.application.subtitle.exception.CorrectionSegmentOutdatedException;
import com.vidsage.application.subtitle.query.CorrectionView;
import com.vidsage.application.task.SubtitleTaskFinder;
import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrection;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrectionField;
import com.vidsage.domain.subtitle.model.translation.TranslatedSegment;
import com.vidsage.domain.subtitle.model.translation.TranslatedTrack;
import com.vidsage.domain.subtitle.port.SubtitleCorrectionRepository;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.task.model.TaskRunStatus;
import com.vidsage.domain.task.model.TaskRunType;
import com.vidsage.domain.task.port.TaskRunRepository;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link SubtitleCorrectionService} 的纯单元测试：端口与 reader 全部 mock，覆盖层用真实实例。
 *
 * <p>按校验表逐行钉住：任务不存在 404、运行中 409、条目过期 409、译文落后于原文 409、
 * 未知 field 词形 / 译文语种缺失或无效 / 空值与超长 400，
 * 以及「源文本记的是保存时用户看到的原文」「清理只删未命中」。
 */
class SubtitleCorrectionServiceTest {

    private static final long TASK_ID = 7L;

    private static final String TASK_NO = "3f2a9c1e7b5d4a6089abcdef01234567";

    private static final Path WORK_DIR = Path.of("work");

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 10, 0);

    private final SubtitleTaskFinder finder = mock(SubtitleTaskFinder.class);

    private final SubtitleCorrectionRepository repository = mock(SubtitleCorrectionRepository.class);

    private final SubtitleTrackReader trackReader = mock(SubtitleTrackReader.class);

    private final TaskRunRepository taskRunRepository = mock(TaskRunRepository.class);

    private final SubtitleCorrectionService service = new SubtitleCorrectionService(finder, repository,
            new SubtitleCorrectionOverlay(repository), trackReader, taskRunRepository);

    @BeforeEach
    void stubTaskAndCleanTrack() {
        SubtitleTask task = SubtitleTask.restore(TASK_ID, new TaskNo(TASK_NO), 1L, NOW, NOW);
        Workspace workspace = Workspace.of(SourceType.URL, new SourceKey("BV11QMd6WEJp|1"),
                "https://example.com/v", "https://example.com/v");
        when(finder.require(TASK_NO)).thenReturn(task);
        when(finder.requireWorkspace(task)).thenReturn(workspace);
        when(finder.workspaceDir(workspace)).thenReturn(WORK_DIR);
        when(taskRunRepository.findLatestByTaskId(TASK_ID)).thenReturn(Optional.empty());
        when(trackReader.readCleanTrack(TASK_ID, WORK_DIR)).thenReturn(Optional.of(cleanTrack()));
    }

    // ---------- 保存：成功路径 ----------

    @Test
    void saveStoresUntouchedMachineTextAsSourceAndTrimsValue() {
        stubUpsertEcho();
        ArgumentCaptor<SubtitleCorrection> captor = ArgumentCaptor.forClass(SubtitleCorrection.class);

        CorrectionView view = service.save(TASK_NO, "seg-0-1000", "text", "en", "  用户改的原文  ");

        verify(repository).upsert(captor.capture());
        SubtitleCorrection written = captor.getValue();
        assertEquals("机器原文 A", written.sourceText(), "源文本记的是改前的机器原文");
        assertEquals("用户改的原文", written.correctedValue(), "首尾空白要去掉");
        assertEquals("", written.lang(), "原文纠错不该带语种");
        assertEquals(SubtitleCorrectionField.TEXT, written.field());
        assertEquals("text", view.field(), "读模型里存的是对外词形");
        assertTrue(view.matched());
        assertFalse(view.sourceChanged(), "刚保存的条目源文本就是当前的");
    }

    @Test
    void saveAcceptsFieldTokenIgnoringCaseAndSurroundingBlanks() {
        stubUpsertEcho();

        service.save(TASK_NO, "seg-0-1000", "  TEXT ", null, "用户改的原文");

        verify(repository).upsert(argThat(correction ->
                correction.field() == SubtitleCorrectionField.TEXT));
    }

    @Test
    void saveNormalizesTranslationLanguageAndStoresEffectiveSourceText() {
        stubUpsertEcho();
        when(trackReader.readTranslatedTrack(TASK_ID, "zh-Hans", WORK_DIR))
                .thenReturn(Optional.of(translatedTrack("zh-Hans", "seg-0-1000")));
        ArgumentCaptor<SubtitleCorrection> captor = ArgumentCaptor.forClass(SubtitleCorrection.class);

        service.save(TASK_NO, "seg-0-1000", "translation", "zh", "改后的译文");

        verify(trackReader).readTranslatedTrack(TASK_ID, "zh-Hans", WORK_DIR);
        verify(repository).upsert(captor.capture());
        assertEquals("zh-Hans", captor.getValue().lang(), "语种别名要归一到规范短码");
        assertEquals("机器原文 A", captor.getValue().sourceText(),
                "没有原文纠错时，用户看到的原文就是机器原文");
        assertEquals(SubtitleCorrectionField.TRANSLATION, captor.getValue().field());
    }

    @Test
    void saveTranslationStoresCorrectedOriginalAsItsBasis() {
        // 用户先改过原文：译文纠错的依据文本必须是改后的那一句，否则「原文后来改过」判不出来
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of(
                new SubtitleCorrection(1L, TASK_ID, "seg-0-1000", SubtitleCorrectionField.TEXT, "",
                        "机器原文 A", "用户改的原文", NOW, NOW)));
        stubUpsertEcho();
        when(trackReader.readTranslatedTrack(TASK_ID, "zh-Hans", WORK_DIR))
                .thenReturn(Optional.of(translatedTrack("zh-Hans", "seg-0-1000")));
        ArgumentCaptor<SubtitleCorrection> captor = ArgumentCaptor.forClass(SubtitleCorrection.class);

        service.save(TASK_NO, "seg-0-1000", "translation", "zh-Hans", "改后的译文");

        verify(repository).upsert(captor.capture());
        assertEquals("用户改的原文", captor.getValue().sourceText(),
                "译文纠错记的是用户当时看到的原文（叠过原文纠错）");
    }

    // ---------- 保存：任务与状态（404 / 409） ----------

    @Test
    void savePropagatesUnknownTask() {
        when(finder.require("ghost")).thenThrow(new TaskNotFoundException("ghost"));

        assertThrows(TaskNotFoundException.class,
                () -> service.save("ghost", "seg-0-1000", "text", null, "改"));
        verifyNoInteractions(repository);
    }

    @Test
    void saveRejectsWhileTaskIsRunning() {
        when(taskRunRepository.findLatestByTaskId(TASK_ID))
                .thenReturn(Optional.of(runningRun(3)));

        TaskConflictException e = assertThrows(TaskConflictException.class,
                () -> service.save(TASK_NO, "seg-0-1000", "text", null, "改"));

        assertEquals(TASK_NO, e.getTaskNo());
        assertEquals(3, e.getRunNo());
        verify(repository, never()).upsert(any());
    }

    // ---------- 保存：条目过期（409） ----------

    @Test
    void saveRejectsSegmentMissingFromCleanTrack() {
        CorrectionSegmentOutdatedException e = assertThrows(CorrectionSegmentOutdatedException.class,
                () -> service.save(TASK_NO, "seg-9-9999", "text", null, "改"));

        assertTrue(e.getMessage().contains("字幕条目已过期，请刷新后重试"));
        verify(repository, never()).upsert(any());
    }

    @Test
    void saveRejectsWhenThereIsNoCleanTrack() {
        when(trackReader.readCleanTrack(TASK_ID, WORK_DIR)).thenReturn(Optional.empty());

        assertThrows(CorrectionSegmentOutdatedException.class,
                () -> service.save(TASK_NO, "seg-0-1000", "text", null, "改"));
        verify(repository, never()).upsert(any());
    }

    @Test
    void saveRejectsTranslationWhenSegmentIsMissingFromThatTranslation() {
        // 原文里有这一条，但这份译文是照更旧的原文翻的：存下去也永远叠不上
        when(trackReader.readTranslatedTrack(TASK_ID, "en", WORK_DIR))
                .thenReturn(Optional.of(translatedTrack("en", "seg-1000-2000")));

        CorrectionSegmentOutdatedException e = assertThrows(CorrectionSegmentOutdatedException.class,
                () -> service.save(TASK_NO, "seg-0-1000", "translation", "en", "改"));

        assertTrue(e.getMessage().contains("译文字幕版本比原文旧"), "要说清是译文落后，不是原文没了");
        assertTrue(e.getMessage().contains("重新翻译"), "给出的下一步是重新翻译，不是刷新");
        verify(repository, never()).upsert(any());
    }

    // ---------- 保存：参数（400） ----------

    @Test
    void saveRejectsUnknownFieldToken() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.save(TASK_NO, "seg-0-1000", "subtitle", null, "改"));

        assertTrue(e.getMessage().contains("text"));
        assertTrue(e.getMessage().contains("translation"));
        verifyNoInteractions(repository);
    }

    @Test
    void saveRejectsMissingFieldToken() {
        assertThrows(IllegalArgumentException.class,
                () -> service.save(TASK_NO, "seg-0-1000", "  ", null, "改"));
        verifyNoInteractions(repository);
    }

    @Test
    void saveRejectsTranslationWithoutLanguage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.save(TASK_NO, "seg-0-1000", "translation", " ", "改"));

        assertTrue(e.getMessage().contains("lang"));
        verifyNoInteractions(repository);
    }

    @Test
    void saveRejectsTranslationWithoutIntactArtifact() {
        when(trackReader.readTranslatedTrack(TASK_ID, "en", WORK_DIR)).thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.save(TASK_NO, "seg-0-1000", "translation", "en", "改"));

        assertTrue(e.getMessage().contains("译文产物"));
        verify(repository, never()).upsert(any());
    }

    @Test
    void saveRejectsBlankValueAfterTrim() {
        assertThrows(IllegalArgumentException.class,
                () -> service.save(TASK_NO, "seg-0-1000", "text", null, "   "));
        verifyNoInteractions(repository);
    }

    @Test
    void saveRejectsOverlongValue() {
        String tooLong = "a".repeat(2001);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.save(TASK_NO, "seg-0-1000", "text", null, tooLong));

        assertTrue(e.getMessage().contains("2000"));
        verifyNoInteractions(repository);
    }

    // ---------- 查询 ----------

    @Test
    void listReportsMatchedAndSourceChangedAgainstCurrentTrack() {
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of(
                new SubtitleCorrection(1L, TASK_ID, "seg-0-1000", SubtitleCorrectionField.TEXT, "",
                        "旧机器原文", "用户改的原文", NOW, NOW),
                new SubtitleCorrection(2L, TASK_ID, "seg-9-9999", SubtitleCorrectionField.TEXT, "",
                        "旧机器原文", "旧条目的改动", NOW, NOW)));

        List<CorrectionView> views = service.list(TASK_NO);

        assertEquals(2, views.size());
        assertEquals("text", views.get(0).field(), "列表里的 field 也是对外词形");
        assertTrue(views.get(0).matched());
        assertTrue(views.get(0).sourceChanged(), "机器文本与保存时不同 → 需要复核");
        assertFalse(views.get(1).matched(), "当前字幕里没有这个 id");
        assertFalse(views.get(1).sourceChanged());
    }

    @Test
    void listStillReturnsEntriesWhenThereIsNoCleanTrack() {
        when(trackReader.readCleanTrack(TASK_ID, WORK_DIR)).thenReturn(Optional.empty());
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of(
                new SubtitleCorrection(1L, TASK_ID, "seg-0-1000", SubtitleCorrectionField.TEXT, "",
                        "机器原文 A", "用户改的原文", NOW, NOW)));

        List<CorrectionView> views = service.list(TASK_NO);

        assertEquals(1, views.size());
        assertTrue(views.get(0).matched(), "判不了失效就不判：读不到清洗字幕时不报失效");
    }

    // ---------- 删除 ----------

    @Test
    void deleteNormalizesLanguageAndDelegates() {
        service.delete(TASK_NO, "seg-0-1000", "translation", "zh");

        verify(repository).deleteByTaskIdAndSegmentIdAndFieldAndLang(TASK_ID, "seg-0-1000",
                SubtitleCorrectionField.TRANSLATION, "zh-Hans");
    }

    @Test
    void deleteForTextDropsLanguage() {
        service.delete(TASK_NO, "seg-0-1000", "text", "en");

        verify(repository).deleteByTaskIdAndSegmentIdAndFieldAndLang(TASK_ID, "seg-0-1000",
                SubtitleCorrectionField.TEXT, "");
    }

    @Test
    void deleteRejectsUnknownFieldToken() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.delete(TASK_NO, "seg-0-1000", "nope", null));

        assertTrue(e.getMessage().contains("translation"));
        verifyNoInteractions(repository);
    }

    @Test
    void deleteRejectsWhileTaskIsRunning() {
        when(taskRunRepository.findLatestByTaskId(TASK_ID)).thenReturn(Optional.of(runningRun(4)));

        assertThrows(TaskConflictException.class,
                () -> service.delete(TASK_NO, "seg-0-1000", "text", null));
        verifyNoInteractions(repository);
    }

    // ---------- 清理失效 ----------

    @Test
    void clearStaleDeletesOnlyUnmatchedEntries() {
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of(
                new SubtitleCorrection(1L, TASK_ID, "seg-0-1000", SubtitleCorrectionField.TEXT, "",
                        "机器原文 A", "还命中的改动", NOW, NOW),
                new SubtitleCorrection(2L, TASK_ID, "seg-9-9999", SubtitleCorrectionField.TEXT, "",
                        "旧机器原文", "失效的改动", NOW, NOW)));
        when(repository.deleteByIds(List.of(2L))).thenReturn(1);

        int removed = service.clearStale(TASK_NO);

        assertEquals(1, removed);
        verify(repository).deleteByIds(List.of(2L));
    }

    @Test
    void clearStaleDeletesNothingWhenCleanTrackIsMissing() {
        when(trackReader.readCleanTrack(TASK_ID, WORK_DIR)).thenReturn(Optional.empty());
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of(
                new SubtitleCorrection(1L, TASK_ID, "seg-9-9999", SubtitleCorrectionField.TEXT, "",
                        "旧机器原文", "失效的改动", NOW, NOW)));

        int removed = service.clearStale(TASK_NO);

        assertEquals(0, removed, "读不到清洗字幕时判不了失效，一条都不删");
        verify(repository, never()).deleteByIds(argThat(ids -> !ids.isEmpty()));
    }

    @Test
    void clearStaleRejectsWhileTaskIsRunning() {
        when(taskRunRepository.findLatestByTaskId(TASK_ID)).thenReturn(Optional.of(runningRun(5)));

        assertThrows(TaskConflictException.class, () -> service.clearStale(TASK_NO));
        verify(repository, never()).deleteByIds(any());
    }

    // ---------- 夹具 ----------

    /** 让仓储把入参原样「读回」并补上 id 与时间戳，模拟真实保存后的样子。 */
    private void stubUpsertEcho() {
        when(repository.upsert(any())).thenAnswer(invocation -> {
            SubtitleCorrection incoming = invocation.getArgument(0);
            return new SubtitleCorrection(11L, incoming.taskId(), incoming.segmentId(), incoming.field(),
                    incoming.lang(), incoming.sourceText(), incoming.correctedValue(), NOW, NOW);
        });
    }

    private static SubtitleTrack cleanTrack() {
        return new SubtitleTrack("zh-Hans", List.of(
                new TranscriptionSegment(0, 1000, "机器原文 A", "seg-0-1000"),
                new TranscriptionSegment(1000, 2000, "机器原文 B", "seg-1000-2000")), null);
    }

    /** 该语种的译文产物：只放一条指定 id 的译文，用来验「译文里有没有这条」。 */
    private static TranslatedTrack translatedTrack(String lang, String segmentId) {
        return new TranslatedTrack("zh-Hans", lang, false, List.of(
                new TranslatedSegment(0, 1000, "机器原文 A", "机器译文 A", segmentId)));
    }

    private static TaskRun runningRun(int runNo) {
        return TaskRun.restore(1L, TASK_ID, runNo, TaskRunType.MAIN, null, TaskRunStatus.RUNNING,
                null, null, NOW, null, NOW);
    }
}
