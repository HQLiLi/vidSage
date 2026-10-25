package com.vidsage.application.subtitle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.domain.subtitle.model.ArtifactFileKind;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrection;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrectionField;
import com.vidsage.domain.subtitle.model.translation.TranslatedSegment;
import com.vidsage.domain.subtitle.model.translation.TranslatedTrack;
import com.vidsage.domain.subtitle.port.SubtitleCorrectionRepository;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link SubtitleArtifactRenderer} 的纯单元测试：机器产物读取与纠错仓储都 mock，
 * 覆盖层用真实实例——「没有纠错就原样返回入参」这条契约正是本类判「要不要重渲」的依据。
 *
 * <p>钉住五件事：分流表、零纠错返回空、原文/译文纠错各自只影响该影响的文件、
 * 其它语种的译文纠错不串过来、读不到机器产物时返回空（调用方直发文件）。
 */
class SubtitleArtifactRendererTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final long TASK_ID = 7L;

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private final SubtitleTrackReader trackReader = mock(SubtitleTrackReader.class);

    private final SubtitleCorrectionRepository correctionRepository = mock(SubtitleCorrectionRepository.class);

    private final SubtitleArtifactRenderer renderer = new SubtitleArtifactRenderer(
            trackReader, new SubtitleCorrectionOverlay(correctionRepository), JSON);

    // ---------- 分流表：与纠错无关的文件位永远不重渲 ----------

    @Test
    void originalIsNeverRendered() {
        assertTrue(renderer.render(TASK_ID, ArtifactType.ORIGINAL, "", ArtifactFileKind.SEGMENTS, workDir)
                .isEmpty());
        assertTrue(renderer.render(TASK_ID, ArtifactType.ORIGINAL, "", ArtifactFileKind.SUBTITLES, workDir)
                .isEmpty());
        verify(trackReader, never()).readCleanTrack(TASK_ID, workDir);
    }

    @Test
    void cleanedContextIsNeverRendered() {
        assertTrue(renderer.render(TASK_ID, ArtifactType.CLEANED, "", ArtifactFileKind.CONTEXT, workDir)
                .isEmpty());
        verify(trackReader, never()).readCleanTrack(TASK_ID, workDir);
    }

    @Test
    void translatedTermsAreNeverRendered() {
        assertTrue(renderer.render(TASK_ID, ArtifactType.TRANSLATED, "zh-Hans", ArtifactFileKind.TERMS, workDir)
                .isEmpty());
        verify(trackReader, never()).readTranslatedTrack(TASK_ID, "zh-Hans", workDir);
    }

    // ---------- 零纠错：走磁盘快路径 ----------

    @Test
    void cleanDownloadWithNoCorrectionsStaysOnTheFastPath() {
        when(trackReader.readCleanTrack(TASK_ID, workDir)).thenReturn(Optional.of(cleanTrack()));
        when(correctionRepository.findByTaskId(TASK_ID)).thenReturn(List.of());

        assertTrue(renderer.render(TASK_ID, ArtifactType.CLEANED, "", ArtifactFileKind.SEGMENTS, workDir)
                .isEmpty(), "没有原文纠错时不该重渲，下载必须直发磁盘文件");
        assertTrue(renderer.render(TASK_ID, ArtifactType.CLEANED, "", ArtifactFileKind.SUBTITLES, workDir)
                .isEmpty());
    }

    @Test
    void translatedDownloadWithNoCorrectionsStaysOnTheFastPath() {
        when(trackReader.readTranslatedTrack(TASK_ID, "zh-Hans", workDir))
                .thenReturn(Optional.of(translatedTrack("zh-Hans")));
        when(correctionRepository.findByTaskId(TASK_ID)).thenReturn(List.of(textCorrection()));

        assertTrue(renderer.render(TASK_ID, ArtifactType.TRANSLATED, "zh-Hans",
                ArtifactFileKind.SEGMENTS, workDir).isEmpty(), "只有原文纠错不该重渲译文");
    }

    // ---------- 原文纠错 ----------

    @Test
    void cleanedSegmentsCarryTextCorrectionAndKeepFingerprint() throws Exception {
        when(trackReader.readCleanTrack(TASK_ID, workDir)).thenReturn(Optional.of(cleanTrack()));
        when(correctionRepository.findByTaskId(TASK_ID)).thenReturn(List.of(textCorrection()));

        byte[] content = renderer.render(TASK_ID, ArtifactType.CLEANED, "",
                ArtifactFileKind.SEGMENTS, workDir).orElseThrow();

        String json = new String(content, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"text\" : \"改后原文\""), "要带上用户改后的原文");
        assertFalse(json.contains("\"text\" : \"机器原文 A\""), "机器原文必须被替换掉");
        assertTrue(json.contains("\"text\" : \"机器原文 B\""), "没命中的段落原样保留");
        assertTrue(json.contains("\"keywordsFingerprint\" : \"fp-1\""), "重渲不能丢掉关键词指纹");
        assertTrue(json.contains("\"id\" : \"seg-0-1000\""), "id 要留在产物里");
    }

    @Test
    void cleanedSrtCarriesTextCorrection() {
        when(trackReader.readCleanTrack(TASK_ID, workDir)).thenReturn(Optional.of(cleanTrack()));
        when(correctionRepository.findByTaskId(TASK_ID)).thenReturn(List.of(textCorrection()));

        String srt = new String(renderer.render(TASK_ID, ArtifactType.CLEANED, "",
                ArtifactFileKind.SUBTITLES, workDir).orElseThrow(), StandardCharsets.UTF_8);

        assertTrue(srt.contains("改后原文"));
        assertFalse(srt.contains("机器原文 A"));
        assertTrue(srt.contains("00:00:00,000 --> 00:00:01,000"), "时间轴原样保留");
        assertTrue(srt.contains("2\n00:00:01,000 --> 00:00:02,000"), "第二段照旧");
    }

    // ---------- 译文纠错：只认该语种 ----------

    @Test
    void translatedSegmentsCarryOnlyTheRequestedLanguage() {
        when(trackReader.readTranslatedTrack(TASK_ID, "zh-Hans", workDir))
                .thenReturn(Optional.of(translatedTrack("zh-Hans")));
        when(correctionRepository.findByTaskId(TASK_ID)).thenReturn(List.of(translationCorrection("zh-Hans")));

        String json = new String(renderer.render(TASK_ID, ArtifactType.TRANSLATED, "zh-Hans",
                ArtifactFileKind.SEGMENTS, workDir).orElseThrow(), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"translatedText\" : \"改后译文\""));
        assertTrue(json.contains("\"sourceText\" : \"机器原文 A\""), "只换译文，原文照旧");
        assertTrue(json.contains("\"targetLanguage\" : \"zh-Hans\""));
    }

    @Test
    void translationCorrectionOfAnotherLanguageDoesNotLeakIn() {
        when(trackReader.readTranslatedTrack(TASK_ID, "zh-Hans", workDir))
                .thenReturn(Optional.of(translatedTrack("zh-Hans")));
        when(correctionRepository.findByTaskId(TASK_ID)).thenReturn(List.of(translationCorrection("en")));

        assertTrue(renderer.render(TASK_ID, ArtifactType.TRANSLATED, "zh-Hans",
                ArtifactFileKind.SEGMENTS, workDir).isEmpty(), "别的语种的译文纠错不能串过来");
    }

    @Test
    void translatedSrtRendersBothLines() {
        when(trackReader.readTranslatedTrack(TASK_ID, "zh-Hans", workDir))
                .thenReturn(Optional.of(translatedTrack("zh-Hans")));
        when(correctionRepository.findByTaskId(TASK_ID)).thenReturn(List.of(translationCorrection("zh-Hans")));

        String srt = new String(renderer.render(TASK_ID, ArtifactType.TRANSLATED, "zh-Hans",
                ArtifactFileKind.SUBTITLES, workDir).orElseThrow(), StandardCharsets.UTF_8);

        assertTrue(srt.contains("机器原文 A\n改后译文"), "双语 SRT 是原文一行、译文一行");
    }

    // ---------- 读不到机器产物：交给调用方直发文件 ----------

    @Test
    void missingMachineTrackFallsBackToTheFile() {
        when(trackReader.readCleanTrack(TASK_ID, workDir)).thenReturn(Optional.empty());
        when(correctionRepository.findByTaskId(TASK_ID)).thenReturn(List.of(textCorrection()));

        assertTrue(renderer.render(TASK_ID, ArtifactType.CLEANED, "",
                ArtifactFileKind.SEGMENTS, workDir).isEmpty());
    }

    @Test
    void emptyMachineTrackStillRenders() {
        // 空转写是合法产物：只要真的有纠错要叠，就按渲染结果发，别因为「叠完还是空」退回磁盘那份
        when(trackReader.readCleanTrack(TASK_ID, workDir))
                .thenReturn(Optional.of(new SubtitleTrack("ja", List.of(), "fp-1")));
        when(correctionRepository.findByTaskId(TASK_ID)).thenReturn(List.of(textCorrection()));

        String json = new String(renderer.render(TASK_ID, ArtifactType.CLEANED, "",
                ArtifactFileKind.SEGMENTS, workDir).orElseThrow(), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"language\" : \"ja\""));
    }

    // ---------- 夹具 ----------

    private static SubtitleTrack cleanTrack() {
        return new SubtitleTrack("ja", List.of(
                new TranscriptionSegment(0, 1000, "机器原文 A"),
                new TranscriptionSegment(1000, 2000, "机器原文 B")), "fp-1");
    }

    private static TranslatedTrack translatedTrack(String lang) {
        return new TranslatedTrack("ja", lang, false, List.of(
                new TranslatedSegment(0, 1000, "机器原文 A", "机器译文 A"),
                new TranslatedSegment(1000, 2000, "机器原文 B", "机器译文 B")));
    }

    private static SubtitleCorrection textCorrection() {
        LocalDateTime now = LocalDateTime.now();
        return new SubtitleCorrection(1L, TASK_ID, "seg-0-1000",
                SubtitleCorrectionField.TEXT, "", "机器原文 A", "改后原文", now, now);
    }

    private static SubtitleCorrection translationCorrection(String lang) {
        LocalDateTime now = LocalDateTime.now();
        return new SubtitleCorrection(2L, TASK_ID, "seg-0-1000",
                SubtitleCorrectionField.TRANSLATION, lang, "机器原文 A", "改后译文", now, now);
    }
}
