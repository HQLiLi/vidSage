package com.vidsage.application.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * {@link SubtitleArtifactStore} 的单元测试：仓储全部 mock，工作区用临时目录放真实文件。
 *
 * <p>重点钉三件事：占位行全空、落定先降级旧版再置新版 READY、可读判定按整版产物算。
 */
class SubtitleArtifactStoreTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private final SubtitleArtifactRepository repository = Mockito.mock(SubtitleArtifactRepository.class);

    private final SubtitleArtifactStore store = new SubtitleArtifactStore(repository);

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

    // ---------- settle ----------

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
    void settleCleanedWritesThreeFileNamesAndHashesAndLeavesTermsNull() throws IOException {
        write("clean-segments.json", "segments");
        write("clean-subtitles.srt", "srt");
        write("context.json", "context");
        SubtitleArtifact creating = creating(ArtifactType.CLEANED, "");

        store.settleArtifact(creating, workDir);

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
    void settleSupersedesOldReadyBeforeMarkingNewOneReady() throws IOException {
        write("original-segments.json", "segments");
        write("original-subtitles.srt", "srt");
        SubtitleArtifact old = ready(5L, ArtifactType.ORIGINAL, "");
        when(repository.findReadyByTaskIdAndTypeAndLang(TASK_ID, ArtifactType.ORIGINAL, ""))
                .thenReturn(Optional.of(old));
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

    private void write(String name, String content) throws IOException {
        Files.writeString(workDir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private static SubtitleArtifact creating(ArtifactType type, String lang) {
        return SubtitleArtifact.restore(11L, TASK_ID, RUN_ID, type, lang, 1, SubtitleArtifactStatus.CREATING,
                null, null, null, null, null, null, null, null, null, null, LocalDateTime.now());
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
