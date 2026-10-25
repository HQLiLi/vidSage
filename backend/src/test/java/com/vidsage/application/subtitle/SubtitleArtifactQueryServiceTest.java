package com.vidsage.application.subtitle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.application.subtitle.exception.ArtifactNotFoundException;
import com.vidsage.application.subtitle.query.ArtifactDownload;
import com.vidsage.application.subtitle.query.ArtifactFileSnapshot;
import com.vidsage.application.subtitle.query.ArtifactSnapshot;
import com.vidsage.application.task.SubtitleTaskFinder;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.domain.exception.UnsupportedLanguageException;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactFileKind;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * {@link SubtitleArtifactQueryService} 的单元测试：查找器与产物仓储门面都 mock，
 * 文件系统用临时目录里的真实文件（清单要读真实大小）。
 */
class SubtitleArtifactQueryServiceTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private final SubtitleTaskFinder finder = Mockito.mock(SubtitleTaskFinder.class);
    private final SubtitleArtifactStore artifactStore = Mockito.mock(SubtitleArtifactStore.class);
    private final SubtitleArtifactRenderer renderer = Mockito.mock(SubtitleArtifactRenderer.class);
    private final SubtitleArtifactQueryService service = new SubtitleArtifactQueryService(finder, artifactStore, renderer);

    private static final long TASK_ID = 7L;
    private static final String TASK_NO = "3f2a9c1e3f2a9c1e3f2a9c1e3f2a9c1e";

    @BeforeEach
    void stubRendererWithoutCorrections() {
        when(renderer.render(anyLong(), any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    // ---------- 清单 ----------

    @Test
    void listSortsByTypeOrderThenLanguage() throws IOException {
        writeAll();
        when(artifactStore.listIntactArtifacts(TASK_ID, workDir)).thenReturn(List.of(
                ready(3L, ArtifactType.TRANSLATED, "zh-Hans"),
                ready(2L, ArtifactType.CLEANED, ""),
                ready(1L, ArtifactType.ORIGINAL, "")));
        stubFinder();

        List<ArtifactSnapshot> snapshots = service.listArtifacts(TASK_NO);

        assertEquals(List.of(ArtifactType.ORIGINAL, ArtifactType.CLEANED, ArtifactType.TRANSLATED),
                snapshots.stream().map(ArtifactSnapshot::type).toList(),
                "类型顺序是枚举语义，翻译排最后");
        assertEquals("zh-Hans", snapshots.get(2).lang());
    }

    @Test
    void listCarriesKeywordsFingerprintOnlyOnCleaned() throws IOException {
        writeAll();
        when(artifactStore.listIntactArtifacts(TASK_ID, workDir)).thenReturn(List.of(
                ready(1L, ArtifactType.ORIGINAL, ""),
                ready(2L, ArtifactType.CLEANED, "")));
        stubFinder();

        List<ArtifactSnapshot> snapshots = service.listArtifacts(TASK_NO);

        assertNull(snapshots.get(0).keywordsFingerprint());
        assertEquals("fp-1", snapshots.get(1).keywordsFingerprint());
    }

    @Test
    void listReportsFileKindsInDeclarationOrder() throws IOException {
        writeAll();
        when(artifactStore.listIntactArtifacts(TASK_ID, workDir)).thenReturn(List.of(
                ready(2L, ArtifactType.CLEANED, "")));
        stubFinder();

        ArtifactSnapshot cleaned = service.listArtifacts(TASK_NO).get(0);

        assertEquals(List.of(ArtifactFileKind.SEGMENTS, ArtifactFileKind.SUBTITLES, ArtifactFileKind.CONTEXT),
                cleaned.files().stream().map(file -> file.kind()).toList());
    }

    @Test
    void listDropsVersionWhoseFileDisappearsBeforeSizeRead() {
        // 完好判定时文件还在、取大小时已被删：整版去掉，不抛 500
        when(artifactStore.listIntactArtifacts(TASK_ID, workDir)).thenReturn(List.of(
                ready(1L, ArtifactType.ORIGINAL, "")));
        stubFinder();

        assertTrue(service.listArtifacts(TASK_NO).isEmpty());
    }

    @Test
    void listPropagatesUnknownTask() {
        when(finder.require(TASK_NO)).thenThrow(new TaskNotFoundException(TASK_NO));

        assertThrows(TaskNotFoundException.class, () -> service.listArtifacts(TASK_NO));
    }

    // ---------- 下载：参数不合法（400） ----------

    @Test
    void openRejectsUnknownType() {
        assertThrows(IllegalArgumentException.class,
                () -> service.openArtifact(TASK_NO, "NOPE", "segments.json", null, false));
    }

    @Test
    void openRejectsUnknownFileToken() {
        assertThrows(IllegalArgumentException.class,
                () -> service.openArtifact(TASK_NO, "ORIGINAL", "original-segments.json", null, false));
    }

    @Test
    void openRejectsFileKindTheTypeDoesNotCarry() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.openArtifact(TASK_NO, "ORIGINAL", "context.json", null, false));

        assertTrue(e.getMessage().contains("context.json"));
    }

    @Test
    void openRejectsTranslatedWithoutLang() {
        assertThrows(IllegalArgumentException.class,
                () -> service.openArtifact(TASK_NO, "TRANSLATED", "segments.json", null, false));
    }

    @Test
    void openRejectsLangOnTypeWithoutLanguageDimension() {
        assertThrows(IllegalArgumentException.class,
                () -> service.openArtifact(TASK_NO, "CLEANED", "segments.json", "zh-Hans", false));
    }

    @Test
    void openRejectsUnsupportedLang() {
        assertThrows(UnsupportedLanguageException.class,
                () -> service.openArtifact(TASK_NO, "TRANSLATED", "segments.json", "fr", false));
    }

    @Test
    void openAcceptsTypeTokenInAnyCase() throws IOException {
        writeAll();
        stubFinder();
        when(artifactStore.findIntactArtifact(TASK_ID, ArtifactType.ORIGINAL, "", workDir))
                .thenReturn(Optional.of(ready(1L, ArtifactType.ORIGINAL, "")));

        ArtifactDownload download = service.openArtifact(TASK_NO, "original", "segments.json", null, false);

        assertEquals(workDir.resolve("original-segments.json"), download.path());
        assertEquals(ArtifactFileKind.SEGMENTS, download.kind());
    }

    // ---------- 下载：产物不可用（404） ----------

    @Test
    void openThrowsNotFoundWhenThereIsNoIntactArtifact() {
        stubFinder();
        when(artifactStore.findIntactArtifact(TASK_ID, ArtifactType.CLEANED, "", workDir))
                .thenReturn(Optional.empty());

        ArtifactNotFoundException e = assertThrows(ArtifactNotFoundException.class,
                () -> service.openArtifact(TASK_NO, "CLEANED", "subtitles.srt", null, false));

        assertTrue(e.getMessage().contains("taskNo=" + TASK_NO));
    }

    @Test
    void openThrowsNotFoundWhenFileColumnIsNull() {
        stubFinder();
        SubtitleArtifact broken = SubtitleArtifact.restore(2L, TASK_ID, 1L, ArtifactType.CLEANED, "", 1,
                SubtitleArtifactStatus.READY, null, "fp-1", "clean-segments.json", null, "context.json",
                null, "a", null, "c", null, LocalDateTime.now());
        when(artifactStore.findIntactArtifact(TASK_ID, ArtifactType.CLEANED, "", workDir))
                .thenReturn(Optional.of(broken));

        assertThrows(ArtifactNotFoundException.class,
                () -> service.openArtifact(TASK_NO, "CLEANED", "subtitles.srt", null, false));
    }

    @Test
    void openPropagatesUnknownTask() {
        when(finder.require(TASK_NO)).thenThrow(new TaskNotFoundException(TASK_NO));

        assertThrows(TaskNotFoundException.class,
                () -> service.openArtifact(TASK_NO, "ORIGINAL", "segments.json", null, false));
    }

    // ---------- 下载：语种归一 ----------

    @Test
    void openNormalizesLanguageAliasBeforeLookup() throws IOException {
        writeAll();
        stubFinder();
        when(artifactStore.findIntactArtifact(TASK_ID, ArtifactType.TRANSLATED, "zh-Hans", workDir))
                .thenReturn(Optional.of(ready(3L, ArtifactType.TRANSLATED, "zh-Hans")));

        ArtifactDownload download = service.openArtifact(TASK_NO, "TRANSLATED", "segments.json", "zh", false);

        assertEquals(workDir.resolve("translated-segments-zh-hans.json"), download.path(),
                "入参 zh 与 zh-Hans 等价，查库用规范码，落盘用小写名字");
    }

    @Test
    void listKeepsTranslatedVersionWithoutTerms() throws IOException {
        writeAll();
        when(artifactStore.listIntactArtifacts(TASK_ID, workDir)).thenReturn(List.of(
                translatedWithoutTerms(3L, "zh-Hans")));
        stubFinder();

        List<ArtifactSnapshot> snapshots = service.listArtifacts(TASK_NO);

        assertEquals(1, snapshots.size());
        assertEquals(List.of(ArtifactFileKind.SEGMENTS, ArtifactFileKind.SUBTITLES),
                snapshots.get(0).files().stream().map(ArtifactFileSnapshot::kind).toList(),
                "可选位没有文件只是少一项，整版仍然列出");
    }

    @Test
    void openTermsReturnsNotFoundWhenVersionHasNoTerms() throws IOException {
        writeAll();
        stubFinder();
        when(artifactStore.findIntactArtifact(TASK_ID, ArtifactType.TRANSLATED, "zh-Hans", workDir))
                .thenReturn(Optional.of(translatedWithoutTerms(3L, "zh-Hans")));

        assertThrows(ArtifactNotFoundException.class,
                () -> service.openArtifact(TASK_NO, "TRANSLATED", "terms.json", "zh-Hans", false));
    }

    // ---------- 下载：纠错叠加 ----------

    @Test
    void openReplacesBodyWhenRendererHasCorrections() throws IOException {
        writeAll();
        stubFinder();
        when(artifactStore.findIntactArtifact(TASK_ID, ArtifactType.CLEANED, "", workDir))
                .thenReturn(Optional.of(ready(2L, ArtifactType.CLEANED, "")));
        byte[] rendered = "改过的字幕".getBytes(StandardCharsets.UTF_8);
        when(renderer.render(TASK_ID, ArtifactType.CLEANED, "", ArtifactFileKind.SEGMENTS, workDir))
                .thenReturn(Optional.of(rendered));

        ArtifactDownload download = service.openArtifact(TASK_NO, "CLEANED", "segments.json", null, false);

        assertArrayEquals(rendered, download.content(), "有纠错时发渲染好的字节");
        assertEquals(workDir.resolve("clean-segments.json"), download.path(), "文件名仍取磁盘那份");
    }

    @Test
    void openSendsFileWhenRendererHasNothingToChange() throws IOException {
        writeAll();
        stubFinder();
        when(artifactStore.findIntactArtifact(TASK_ID, ArtifactType.CLEANED, "", workDir))
                .thenReturn(Optional.of(ready(2L, ArtifactType.CLEANED, "")));

        ArtifactDownload download = service.openArtifact(TASK_NO, "CLEANED", "subtitles.srt", null, false);

        assertNull(download.content(), "零纠错走磁盘快路径，正文必须与原文件一个字节都不差");
    }

    @Test
    void openRawBypassesRendererEvenWithCorrections() throws IOException {
        writeAll();
        stubFinder();
        when(artifactStore.findIntactArtifact(TASK_ID, ArtifactType.CLEANED, "", workDir))
                .thenReturn(Optional.of(ready(2L, ArtifactType.CLEANED, "")));

        ArtifactDownload download = service.openArtifact(TASK_NO, "CLEANED", "segments.json", null, true);

        assertNull(download.content(), "?raw=1 永远发磁盘原文件");
        verify(renderer, never()).render(anyLong(), any(), any(), any(), any());
    }

    @Test
    void openPassesNormalizedLangAndFileKindToRenderer() throws IOException {
        writeAll();
        stubFinder();
        when(artifactStore.findIntactArtifact(TASK_ID, ArtifactType.TRANSLATED, "zh-Hans", workDir))
                .thenReturn(Optional.of(ready(3L, ArtifactType.TRANSLATED, "zh-Hans")));

        service.openArtifact(TASK_NO, "TRANSLATED", "subtitles.srt", "zh", false);

        verify(renderer).render(TASK_ID, ArtifactType.TRANSLATED, "zh-Hans",
                ArtifactFileKind.SUBTITLES, workDir);
    }

    // ---------- 夹具 ----------

    private void stubFinder() {
        SubtitleTask task = SubtitleTask.restore(TASK_ID, new TaskNo(TASK_NO), 1L,
                LocalDateTime.now(), LocalDateTime.now());
        Workspace workspace = Workspace.of(SourceType.URL, new SourceKey("BV11QMd6WEJp|1"),
                "https://example.com/v", "https://example.com/v");
        when(finder.require(TASK_NO)).thenReturn(task);
        when(finder.requireWorkspace(task)).thenReturn(workspace);
        when(finder.workspaceDir(workspace)).thenReturn(workDir);
    }

    private void writeAll() throws IOException {
        for (String name : List.of("original-segments.json", "original-subtitles.srt",
                "clean-segments.json", "clean-subtitles.srt", "context.json",
                "translated-segments-zh-hans.json", "translated-subtitles-zh-hans.srt",
                "terms-zh-hans.json")) {
            Files.writeString(workDir.resolve(name), "content", StandardCharsets.UTF_8);
        }
    }

    private static SubtitleArtifact translatedWithoutTerms(long id, String lang) {
        return SubtitleArtifact.restore(id, TASK_ID, 1L, ArtifactType.TRANSLATED, lang, 1,
                SubtitleArtifactStatus.READY, null, null, "translated-segments-zh-hans.json",
                "translated-subtitles-zh-hans.srt", null, null, "a", "b", null, null, LocalDateTime.now());
    }

    private static SubtitleArtifact ready(long id, ArtifactType type, String lang) {
        return switch (type) {
            case ORIGINAL -> SubtitleArtifact.restore(id, TASK_ID, 1L, type, lang, 1,
                    SubtitleArtifactStatus.READY, null, null, "original-segments.json",
                    "original-subtitles.srt", null, null, "a", "b", null, null, LocalDateTime.now());
            case CLEANED -> SubtitleArtifact.restore(id, TASK_ID, 1L, type, lang, 2,
                    SubtitleArtifactStatus.READY, null, "fp-1", "clean-segments.json",
                    "clean-subtitles.srt", "context.json", null, "a", "b", "c", null, LocalDateTime.now());
            case TRANSLATED -> SubtitleArtifact.restore(id, TASK_ID, 1L, type, lang, 1,
                    SubtitleArtifactStatus.READY, null, null, "translated-segments-zh-hans.json",
                    "translated-subtitles-zh-hans.srt", null, "terms-zh-hans.json", "a", "b", null, "c",
                    LocalDateTime.now());
        };
    }
}
