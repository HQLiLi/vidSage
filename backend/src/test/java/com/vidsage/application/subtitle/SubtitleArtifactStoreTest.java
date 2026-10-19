package com.vidsage.application.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
import com.vidsage.domain.subtitle.port.SubtitleArtifactRepository;
import com.vidsage.shared.fs.ArtifactFileUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * {@link SubtitleArtifactStore} 的单元测试：仓储全部 mock，工作区用临时目录放真实文件。
 *
 * <p>重点钉四件事：占位行全空、落定先降级旧版再置新版 READY、可读判定按整版产物算、
 * 清洗落定只在内容真变了时作废下游并把它们的文件收掉。
 */
class SubtitleArtifactStoreTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private final SubtitleArtifactRepository repository = Mockito.mock(SubtitleArtifactRepository.class);

    /** 事务事件与仓储写共用一张表，这样「三段写都在事务里」才钉得住。 */
    private final List<String> events = new ArrayList<>();

    private final RecordingTransactionManager transactionManager = new RecordingTransactionManager(events);

    private final SubtitleArtifactStore store = new SubtitleArtifactStore(repository, transactionManager);

    private static final long TASK_ID = 7L;
    private static final long RUN_ID = 1L;

    // ---------- begin ----------

    @Test
    void beginPersistsCreatingPlaceholderWithNullFileColumns() {
        when(repository.insertCreating(any())).thenAnswer(invocation -> invocation.getArgument(0));

        store.createCreatingPlaceholder(TASK_ID, RUN_ID, ArtifactType.CLEANED, "", 5L, "fp-1");

        ArgumentCaptor<SubtitleArtifact> captor = ArgumentCaptor.forClass(SubtitleArtifact.class);
        verify(repository).insertCreating(captor.capture());
        SubtitleArtifact inserted = captor.getValue();
        assertEquals(SubtitleArtifactStatus.CREATING, inserted.getStatus());
        assertEquals(ArtifactType.CLEANED, inserted.getArtifactType());
        assertEquals("", inserted.getLang());
        assertEquals(5L, inserted.getDerivedFromArtifactId());
        assertEquals("fp-1", inserted.getKeywordsFingerprint());
        assertNull(inserted.getSegmentsFileName(), "占位行文件列必须全空");
        assertNull(inserted.getSubtitlesFileName());
        assertNull(inserted.getContextFileName());
        assertNull(inserted.getTermsFileName());
        assertNull(inserted.getSegmentsSha256());
    }

    // ---------- settle：没有下游的类型 ----------

    @Test
    void settleOriginalWritesTwoFileNamesAndHashesAndLeavesContextAndTermsNull() throws IOException {
        write("original-segments.json", "segments");
        write("original-subtitles.srt", "srt");
        SubtitleArtifact creating = creating(ArtifactType.ORIGINAL, "");

        store.settleArtifact(creating, workDir);

        assertEquals(SubtitleArtifactStatus.READY, creating.getStatus());
        assertEquals("original-segments.json", creating.getSegmentsFileName());
        assertEquals("original-subtitles.srt", creating.getSubtitlesFileName());
        assertNull(creating.getContextFileName());
        assertNull(creating.getTermsFileName());
        assertNotNull(creating.getSegmentsSha256());
        assertNotNull(creating.getSubtitlesSha256());
        assertNull(creating.getContextSha256());
        assertNull(creating.getTermsSha256());
        verify(repository).updateReady(creating);
    }

    @Test
    void settleSupersedesOldReadyBeforeMarkingNewOneReady() throws IOException {
        write("original-segments.json", "segments");
        write("original-subtitles.srt", "srt");
        SubtitleArtifact previousReadyOriginal = ready(5L, ArtifactType.ORIGINAL, "");
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.ORIGINAL, ""))
                .thenReturn(Optional.of(previousReadyOriginal));
        SubtitleArtifact creating = creating(ArtifactType.ORIGINAL, "");

        store.settleArtifact(creating, workDir);

        InOrder order = inOrder(repository);
        order.verify(repository).markSupersededAndClearFiles(5L);
        order.verify(repository).updateReady(creating);
    }

    @Test
    void settleSkipsSupersedeWhenThereIsNoOldReady() throws IOException {
        write("original-segments.json", "segments");
        write("original-subtitles.srt", "srt");
        when(repository.findReadyByTaskIdAndTypeAndLang(anyLong(), any(), any())).thenReturn(Optional.empty());

        store.settleArtifact(creating(ArtifactType.ORIGINAL, ""), workDir);

        verify(repository, never()).markSupersededAndClearFiles(anyLong());
    }

    @Test
    void settleThrowsWithoutWritingAnythingWhenAFileIsMissing() throws IOException {
        write("original-segments.json", "segments");
        SubtitleArtifact creating = creating(ArtifactType.ORIGINAL, "");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> store.settleArtifact(creating, workDir));

        assertTrue(e.getMessage().contains("subtitles.srt"), "消息要指出缺的是哪个文件位");
        assertTrue(events.isEmpty(), "算哈希是事务外的事，失败时事务根本不该开");
        verify(repository, never()).markSupersededAndClearFiles(anyLong());
        verify(repository, never()).updateReady(any());
    }

    @Test
    void settleUsesLowerCaseLanguageInTranslatedFileName() throws IOException {
        write("translated-segments-zh-hans.json", "segments");
        write("translated-subtitles-zh-hans.srt", "srt");
        write("terms-zh-hans.json", "terms");
        SubtitleArtifact creating = creating(ArtifactType.TRANSLATED, "zh-Hans");

        store.settleArtifact(creating, workDir);

        assertEquals("translated-segments-zh-hans.json", creating.getSegmentsFileName(),
                "库里的语种是 zh-Hans，盘上文件名是小写，落定要按盘上的名字");
        assertEquals("terms-zh-hans.json", creating.getTermsFileName());
        assertNull(creating.getContextFileName());
    }

    @Test
    void settleTranslatedWithoutTermsRegistersOnlySegmentsAndSubtitles() throws IOException {
        write("translated-segments-en.json", "segments");
        write("translated-subtitles-en.srt", "srt");
        SubtitleArtifact creating = creating(ArtifactType.TRANSLATED, "en");

        store.settleArtifact(creating, workDir);

        assertEquals(SubtitleArtifactStatus.READY, creating.getStatus());
        assertEquals("translated-segments-en.json", creating.getSegmentsFileName());
        assertEquals("translated-subtitles-en.srt", creating.getSubtitlesFileName());
        assertNull(creating.getTermsFileName(), "术语查证没产出时 terms 列留空，不算失败");
        assertNull(creating.getTermsSha256());
        assertNotNull(creating.getSegmentsSha256());
        assertNotNull(creating.getSubtitlesSha256());
    }

    @Test
    void settleTranslatedStillRegistersTermsWhenPresent() throws IOException {
        write("translated-segments-en.json", "segments");
        write("translated-subtitles-en.srt", "srt");
        write("terms-en.json", "terms");
        SubtitleArtifact creating = creating(ArtifactType.TRANSLATED, "en");

        store.settleArtifact(creating, workDir);

        assertEquals("terms-en.json", creating.getTermsFileName());
        assertNotNull(creating.getTermsSha256());
    }

    // ---------- settle：清洗（换版 + 下游级联）----------

    @Test
    void settleCleanedWritesThreeFileNamesAndHashesAndLeavesTermsNull() throws IOException {
        writeCleanedFiles();

        SubtitleArtifact creating = creating(ArtifactType.CLEANED, "");
        store.settleCleanedAndSupersedeDownstream(creating, workDir);

        assertEquals("clean-segments.json", creating.getSegmentsFileName());
        assertEquals("clean-subtitles.srt", creating.getSubtitlesFileName());
        assertEquals("context.json", creating.getContextFileName());
        assertNull(creating.getTermsFileName());
        assertNotNull(creating.getSegmentsSha256());
        assertNotNull(creating.getSubtitlesSha256());
        assertNotNull(creating.getContextSha256());
        assertNull(creating.getTermsSha256());
    }

    @Test
    void settleCleanedSupersedesOldThenCascadesThenMarksNewReady() throws IOException {
        writeCleanedFiles();
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.CLEANED, ""))
                .thenReturn(Optional.of(ready(5L, ArtifactType.CLEANED, "")));
        when(repository.findReadyByTaskId(TASK_ID))
                .thenReturn(List.of(ready(6L, ArtifactType.TRANSLATED, "en")));
        SubtitleArtifact creating = creating(ArtifactType.CLEANED, "");

        store.settleCleanedAndSupersedeDownstream(creating, workDir);

        InOrder order = inOrder(repository);
        order.verify(repository).markSupersededAndClearFiles(5L);
        order.verify(repository).markSupersededByTaskIdAndType(TASK_ID, ArtifactType.TRANSLATED);
        order.verify(repository).updateReady(creating);
    }

    @Test
    void settleCleanedKeepsDownstreamWhenContentIsUnchanged() throws IOException {
        writeCleanedFiles();
        SubtitleArtifact unchangedCleaned = cleanedReadyWithCurrentHashes(5L);
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.CLEANED, ""))
                .thenReturn(Optional.of(unchangedCleaned));
        when(repository.findReadyByTaskId(TASK_ID))
                .thenReturn(List.of(ready(6L, ArtifactType.TRANSLATED, "en")));

        store.settleCleanedAndSupersedeDownstream(creating(ArtifactType.CLEANED, ""), workDir);

        verify(repository).markSupersededAndClearFiles(5L);   // 换版照做
        verify(repository, never()).markSupersededByTaskIdAndType(anyLong(), any()); // 内容没变，下游一个都不动
    }

    @Test
    void settleCleanedTreatsMissingOldHashesAsChangedContent() throws IOException {
        writeCleanedFiles();
        SubtitleArtifact cleanedWithoutHashes = SubtitleArtifact.restore(5L, TASK_ID, RUN_ID, ArtifactType.CLEANED, "",
                1, SubtitleArtifactStatus.READY, null, "fp-1", "clean-segments.json", "clean-subtitles.srt",
                "context.json", null, null, null, null, null, LocalDateTime.now());
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.CLEANED, ""))
                .thenReturn(Optional.of(cleanedWithoutHashes));
        when(repository.findReadyByTaskId(TASK_ID))
                .thenReturn(List.of(ready(6L, ArtifactType.TRANSLATED, "en")));

        store.settleCleanedAndSupersedeDownstream(creating(ArtifactType.CLEANED, ""), workDir);

        verify(repository).markSupersededByTaskIdAndType(TASK_ID, ArtifactType.TRANSLATED);
    }

    @Test
    void settleCleanedCascadesWhenThereIsNoOldReady() throws IOException {
        writeCleanedFiles();
        when(repository.findReadyByTaskId(TASK_ID))
                .thenReturn(List.of(ready(6L, ArtifactType.TRANSLATED, "en")));

        store.settleCleanedAndSupersedeDownstream(creating(ArtifactType.CLEANED, ""), workDir);

        verify(repository, never()).markSupersededAndClearFiles(anyLong());
        verify(repository).markSupersededByTaskIdAndType(TASK_ID, ArtifactType.TRANSLATED);
    }

    @Test
    void settleCleanedThrowsBeforeTouchingTheDatabaseWhenAFileIsMissing() throws IOException {
        write("clean-segments.json", "segments");

        assertThrows(IllegalStateException.class,
                () -> store.settleCleanedAndSupersedeDownstream(creating(ArtifactType.CLEANED, ""), workDir));

        assertTrue(events.isEmpty(), "算哈希是事务外的事，失败时事务根本不该开");
        verify(repository, never()).markSupersededAndClearFiles(anyLong());
        verify(repository, never()).updateReady(any());
    }

    // ---------- 下游回收（两阶段第二步）----------

    @Test
    void settleCleanedDeletesDownstreamFilesAndMarksDeleted() throws IOException {
        writeCleanedFiles();
        write("translated-segments-en.json", "translated");
        write("translated-subtitles-en.srt", "translated");
        write("terms-en.json", "terms");
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.CLEANED, ""))
                .thenReturn(Optional.of(ready(5L, ArtifactType.CLEANED, "")));
        when(repository.findReadyByTaskId(TASK_ID))
                .thenReturn(List.of(ready(6L, ArtifactType.TRANSLATED, "en")));

        store.settleCleanedAndSupersedeDownstream(creating(ArtifactType.CLEANED, ""), workDir);

        assertFalse(Files.exists(workDir.resolve("translated-segments-en.json")));
        assertFalse(Files.exists(workDir.resolve("translated-subtitles-en.srt")));
        assertFalse(Files.exists(workDir.resolve("terms-en.json")));
        verify(repository).markDeleted(6L);
        assertEquals(List.of("begin", "commit"), events, "删文件必须发生在事务提交之后");
    }

    @Test
    void settleCleanedReclaimsDownstreamTranslationWithoutTerms() throws IOException {
        writeCleanedFiles();
        write("translated-segments-en.json", "translated");
        write("translated-subtitles-en.srt", "translated");
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.CLEANED, ""))
                .thenReturn(Optional.of(ready(5L, ArtifactType.CLEANED, "")));
        when(repository.findReadyByTaskId(TASK_ID))
                .thenReturn(List.of(translatedReadyWithoutTerms(6L, "en")));

        store.settleCleanedAndSupersedeDownstream(creating(ArtifactType.CLEANED, ""), workDir);

        assertFalse(Files.exists(workDir.resolve("translated-segments-en.json")));
        assertFalse(Files.exists(workDir.resolve("translated-subtitles-en.srt")));
        verify(repository).markDeleted(6L);
    }

    @Test
    void settleCleanedKeepsRunAliveWhenReclaimFailsAfterCommit() throws IOException {
        writeCleanedFiles();
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.CLEANED, ""))
                .thenReturn(Optional.of(ready(5L, ArtifactType.CLEANED, "")));
        when(repository.findReadyByTaskId(TASK_ID))
                .thenReturn(List.of(ready(6L, ArtifactType.TRANSLATED, "en")));
        when(repository.markDeleted(anyLong())).thenThrow(new IllegalStateException("落库失败"));

        // 新版已经 READY、事务也提交了，回收这一段失败不该把整趟判成失败
        SubtitleArtifact creating = creating(ArtifactType.CLEANED, "");

        store.settleCleanedAndSupersedeDownstream(creating, workDir);

        assertEquals(SubtitleArtifactStatus.READY, creating.getStatus());
    }

    @Test
    void settleCleanedKeepsRowSupersededWhenDeletingAFileFails() throws IOException {
        writeCleanedFiles();
        // 拿一个非空目录冒充文件：deleteIfExists 删不掉它，会抛 IOException
        Path undeletable = Files.createDirectory(workDir.resolve("translated-segments-en.json"));
        Files.writeString(undeletable.resolve("inner"), "x");
        write("translated-subtitles-en.srt", "translated");
        write("terms-en.json", "terms");
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.CLEANED, ""))
                .thenReturn(Optional.of(ready(5L, ArtifactType.CLEANED, "")));
        when(repository.findReadyByTaskId(TASK_ID))
                .thenReturn(List.of(ready(6L, ArtifactType.TRANSLATED, "en")));

        store.settleCleanedAndSupersedeDownstream(creating(ArtifactType.CLEANED, ""), workDir);

        verify(repository, never()).markDeleted(anyLong());
    }

    @Test
    void settleCleanedKeepsRowSupersededWhenAFileColumnIsMissing() throws IOException {
        writeCleanedFiles();
        SubtitleArtifact halfRegisteredTranslation = SubtitleArtifact.restore(6L, TASK_ID, RUN_ID, ArtifactType.TRANSLATED,
                "en", 1, SubtitleArtifactStatus.READY, null, null, "translated-segments-en.json", null,
                null, "terms-en.json", "a", null, null, "c", LocalDateTime.now());
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.CLEANED, ""))
                .thenReturn(Optional.of(ready(5L, ArtifactType.CLEANED, "")));
        when(repository.findReadyByTaskId(TASK_ID)).thenReturn(List.of(halfRegisteredTranslation));

        store.settleCleanedAndSupersedeDownstream(creating(ArtifactType.CLEANED, ""), workDir);

        verify(repository, never()).markDeleted(anyLong());
    }

    // ---------- 事务包裹 ----------

    @Test
    void settleWrapsSupersedeAndMarkReadyInOneTransaction() throws IOException {
        write("original-segments.json", "segments");
        write("original-subtitles.srt", "srt");
        recordRepositoryWrites();
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.ORIGINAL, ""))
                .thenReturn(Optional.of(ready(5L, ArtifactType.ORIGINAL, "")));

        store.settleArtifact(creating(ArtifactType.ORIGINAL, ""), workDir);

        assertEquals(List.of("begin", "write:markSupersededAndClearFiles", "write:updateReady", "commit"),
                events, "旧行降级与新行 READY 必须夹在 begin 与 commit 之间");
    }

    @Test
    void settleRollsBackWhenMarkingNewOneReadyFails() throws IOException {
        write("original-segments.json", "segments");
        write("original-subtitles.srt", "srt");
        recordRepositoryWrites();
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.ORIGINAL, ""))
                .thenReturn(Optional.of(ready(5L, ArtifactType.ORIGINAL, "")));
        Mockito.doAnswer(invocation -> {
            events.add("write:updateReady");
            throw new IllegalStateException("落库失败");
        }).when(repository).updateReady(any());

        assertThrows(IllegalStateException.class,
                () -> store.settleArtifact(creating(ArtifactType.ORIGINAL, ""), workDir));

        assertEquals(List.of("begin", "write:markSupersededAndClearFiles", "write:updateReady", "rollback"),
                events, "异常必须从方法里传出去，且触发回滚而不是提交");
    }

    @Test
    void settleCleanedWrapsThreeWritesInOneTransaction() throws IOException {
        writeCleanedFiles();
        recordRepositoryWrites();
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.CLEANED, ""))
                .thenReturn(Optional.of(ready(5L, ArtifactType.CLEANED, "")));
        when(repository.findReadyByTaskId(TASK_ID))
                .thenReturn(List.of(ready(6L, ArtifactType.TRANSLATED, "en")));

        store.settleCleanedAndSupersedeDownstream(creating(ArtifactType.CLEANED, ""), workDir);

        assertEquals(List.of("begin",
                        "write:markSupersededAndClearFiles",
                        "write:markSupersededByTaskIdAndType",
                        "write:updateReady",
                        "commit",
                        "write:markDeleted"),
                events, "三段换版写必须夹在 begin 与 commit 之间；回收在事务提交之后");
    }

    @Test
    void settleCleanedRollsBackWhenMarkingNewOneReadyFails() throws IOException {
        writeCleanedFiles();
        recordRepositoryWrites();
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.CLEANED, ""))
                .thenReturn(Optional.of(ready(5L, ArtifactType.CLEANED, "")));
        when(repository.findReadyByTaskId(TASK_ID))
                .thenReturn(List.of(ready(6L, ArtifactType.TRANSLATED, "en")));
        // 覆盖默认桩：最后一步落库失败，前面两段写已经记过账了
        Mockito.doAnswer(invocation -> {
            events.add("write:updateReady");
            throw new IllegalStateException("落库失败");
        }).when(repository).updateReady(any());

        assertThrows(IllegalStateException.class,
                () -> store.settleCleanedAndSupersedeDownstream(creating(ArtifactType.CLEANED, ""), workDir));

        assertEquals(List.of("begin",
                        "write:markSupersededAndClearFiles",
                        "write:markSupersededByTaskIdAndType",
                        "write:updateReady",
                        "rollback"),
                events, "异常必须从方法里传出去，且触发回滚而不是提交");
    }

    // ---------- 可读判定 ----------

    @Test
    void findReadableReadyReturnsArtifactWhenAllFilesArePresent() throws IOException {
        write("original-segments.json", "segments");
        write("original-subtitles.srt", "srt");
        SubtitleArtifact ready = ready(5L, ArtifactType.ORIGINAL, "");
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.ORIGINAL, ""))
                .thenReturn(Optional.of(ready));

        assertTrue(store.findReadableReady(TASK_ID, ArtifactType.ORIGINAL, "", workDir).isPresent());
    }

    @Test
    void findReadableReadyTreatsZeroByteFileAsPresent() throws IOException {
        write("original-segments.json", "segments");
        write("original-subtitles.srt", "");
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.ORIGINAL, ""))
                .thenReturn(Optional.of(ready(5L, ArtifactType.ORIGINAL, "")));

        assertTrue(store.findReadableReady(TASK_ID, ArtifactType.ORIGINAL, "", workDir).isPresent(),
                "空转写产出的 0 字节 SRT 是合法产物");
    }

    @Test
    void findReadableReadyReturnsEmptyWhenOneFileIsMissing() throws IOException {
        write("original-segments.json", "segments");
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.ORIGINAL, ""))
                .thenReturn(Optional.of(ready(5L, ArtifactType.ORIGINAL, "")));

        assertTrue(store.findReadableReady(TASK_ID, ArtifactType.ORIGINAL, "", workDir).isEmpty(),
                "缺一个文件就整版当没有");
    }

    @Test
    void findReadableReadyReturnsEmptyWhenFileColumnIsNull() throws IOException {
        write("original-segments.json", "segments");
        write("original-subtitles.srt", "srt");
        SubtitleArtifact broken = SubtitleArtifact.restore(5L, TASK_ID, RUN_ID, ArtifactType.ORIGINAL, "",
                1, SubtitleArtifactStatus.READY, null, null, "original-segments.json", null,
                null, null, "a", null, null, null, LocalDateTime.now());
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.ORIGINAL, ""))
                .thenReturn(Optional.of(broken));

        assertTrue(store.findReadableReady(TASK_ID, ArtifactType.ORIGINAL, "", workDir).isEmpty());
    }

    @Test
    void findReadableReadyAcceptsTranslatedWithoutTerms() throws IOException {
        write("translated-segments-en.json", "segments");
        write("translated-subtitles-en.srt", "srt");
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.TRANSLATED, "en"))
                .thenReturn(Optional.of(translatedReadyWithoutTerms(6L, "en")));

        assertTrue(store.findReadableReady(TASK_ID, ArtifactType.TRANSLATED, "en", workDir).isPresent(),
                "术语表是可选位：没有它整版仍然可读");
    }

    @Test
    void findReadableReadyReturnsEmptyWhenRegisteredTermsFileIsGone() throws IOException {
        write("translated-segments-en.json", "segments");
        write("translated-subtitles-en.srt", "srt");
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.TRANSLATED, "en"))
                .thenReturn(Optional.of(ready(6L, ArtifactType.TRANSLATED, "en")));

        assertTrue(store.findReadableReady(TASK_ID, ArtifactType.TRANSLATED, "en", workDir).isEmpty(),
                "登记了但文件被删：整版不可用，等下一次翻译自愈");
    }

    @Test
    void listReadableReadyFiltersOutWholeVersionsWithMissingFiles() throws IOException {
        write("original-segments.json", "segments");
        write("original-subtitles.srt", "srt");
        write("clean-segments.json", "segments");
        when(repository.findReadyByTaskId(TASK_ID)).thenReturn(List.of(
                ready(5L, ArtifactType.ORIGINAL, ""),
                ready(6L, ArtifactType.CLEANED, "")));

        List<SubtitleArtifact> readable = store.listReadableReady(TASK_ID, workDir);

        assertEquals(1, readable.size());
        assertEquals(ArtifactType.ORIGINAL, readable.get(0).getArtifactType());
    }

    // ---------- 夹具 ----------

    /**
     * 记录型事务管理器替身：只把 begin / commit / rollback 记进一个列表，不碰真库。
     *
     * <p>它验的是「三段写被同一个事务包住」与「失败时回滚被触发」，<b>不是</b>「数据库真的回滚了」——
     * 后者是 Spring 事务管理器的语义，本层不重复实现。
     */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final List<String> events;

        RecordingTransactionManager(List<String> events) {
            this.events = events;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }
    }

    /**
     * 让仓储的每次写也往事件表里记一笔，并把该写当作正常返回。
     * 只记事务事件是不够的：那样「三段写被同一个事务包住」根本没有证据。
     *
     * <p>一律用 {@code doAnswer(...).when(...)} 而不是 {@code when(mock.method())}：后者会先执行
     * 已装的桩，把事件表污染出一条不属于本次调用的记录。
     */
    private void recordRepositoryWrites() {
        Mockito.doAnswer(invocation -> {
            events.add("write:markSupersededAndClearFiles");
            return 1;
        }).when(repository).markSupersededAndClearFiles(anyLong());
        Mockito.doAnswer(invocation -> {
            events.add("write:markSupersededByTaskIdAndType");
            return 1;
        }).when(repository).markSupersededByTaskIdAndType(anyLong(), any());
        Mockito.doAnswer(invocation -> {
            events.add("write:updateReady");
            return 1;
        }).when(repository).updateReady(any());
        Mockito.doAnswer(invocation -> {
            events.add("write:markDeleted");
            return 1;
        }).when(repository).markDeleted(anyLong());
    }

    private void writeCleanedFiles() throws IOException {
        write("clean-segments.json", "segments");
        write("clean-subtitles.srt", "srt");
        write("context.json", "context");
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(workDir.resolve(name), content, StandardCharsets.UTF_8);
    }

    /**
     * 一版清洗产物，它的哈希列就是当前盘上那三个文件算出来的——用来造「内容没变」的场景。
     */
    private SubtitleArtifact cleanedReadyWithCurrentHashes(long id) {
        return SubtitleArtifact.restore(id, TASK_ID, RUN_ID, ArtifactType.CLEANED, "", 1,
                SubtitleArtifactStatus.READY, null, "fp-1", "clean-segments.json", "clean-subtitles.srt",
                "context.json", null, sha("clean-segments.json"), sha("clean-subtitles.srt"),
                sha("context.json"), null, LocalDateTime.now());
    }

    private String sha(String fileName) {
        return ArtifactFileUtils.sha256(workDir.resolve(fileName));
    }

    private static SubtitleArtifact creating(ArtifactType type, String lang) {
        return SubtitleArtifact.restore(11L, TASK_ID, RUN_ID, type, lang, 1, SubtitleArtifactStatus.CREATING,
                null, null, null, null, null, null, null, null, null, null, LocalDateTime.now());
    }

    /**
     * 一版没有术语表的译文：terms 两列都为空（术语查证没产出的正常形态）。
     */
    private static SubtitleArtifact translatedReadyWithoutTerms(long id, String lang) {
        return SubtitleArtifact.restore(id, TASK_ID, RUN_ID, ArtifactType.TRANSLATED, lang, 1,
                SubtitleArtifactStatus.READY, null, null, "translated-segments-en.json",
                "translated-subtitles-en.srt", null, null, "a", "b", null, null, LocalDateTime.now());
    }

    private static SubtitleArtifact ready(long id, ArtifactType type, String lang) {
        return switch (type) {
            case ORIGINAL -> SubtitleArtifact.restore(id, TASK_ID, RUN_ID, type, lang, 1,
                    SubtitleArtifactStatus.READY, null, null, "original-segments.json",
                    "original-subtitles.srt", null, null, "a", "b", null, null, LocalDateTime.now());
            case CLEANED -> SubtitleArtifact.restore(id, TASK_ID, RUN_ID, type, lang, 1,
                    SubtitleArtifactStatus.READY, null, "fp-1", "clean-segments.json",
                    "clean-subtitles.srt", "context.json", null, "a", "b", "c", null, LocalDateTime.now());
            case TRANSLATED -> SubtitleArtifact.restore(id, TASK_ID, RUN_ID, type, lang, 1,
                    SubtitleArtifactStatus.READY, null, null, "translated-segments-en.json",
                    "translated-subtitles-en.srt", null, "terms-en.json", "a", "b", null, "c",
                    LocalDateTime.now());
        };
    }
}
