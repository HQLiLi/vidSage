package com.vidsage.application.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vidsage.application.subtitle.query.CorrectionView;
import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrection;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrectionField;
import com.vidsage.domain.subtitle.model.translation.TranslatedSegment;
import com.vidsage.domain.subtitle.model.translation.TranslatedTrack;
import com.vidsage.domain.subtitle.port.SubtitleCorrectionRepository;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link SubtitleCorrectionOverlay} 的纯单元测试：仓储 mock，覆盖层本身不碰文件。
 *
 * <p>钉住四件事：原文 / 译文按 id 叠上去、语种不串、入参不被改、重复 id 全部命中。
 */
class SubtitleCorrectionOverlayTest {

    private static final long TASK_ID = 7L;

    private final SubtitleCorrectionRepository repository = mock(SubtitleCorrectionRepository.class);

    private final SubtitleCorrectionOverlay overlay = new SubtitleCorrectionOverlay(repository);

    @Test
    void appliesTextCorrectionWithoutMutatingInputTrack() {
        SubtitleTrack original = cleanTrack();
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of(
                textCorrection(1L, "seg-0-1000", "用户改的原文")));

        SubtitleTrack result = overlay.applyToCleanTrack(TASK_ID, original);

        assertEquals("用户改的原文", result.segments().get(0).text());
        assertEquals("机器原文 B", result.segments().get(1).text(), "没命中的段原样保留");
        assertEquals("机器原文 A", original.segments().get(0).text(), "入参 track 不得被改");
        assertEquals("seg-0-1000", result.segments().get(0).id(), "id 与时间戳原样带过");
        assertEquals(1000, result.segments().get(0).endTimeMs());
    }

    @Test
    void appliesTranslationOfSameLanguageOnly() {
        TranslatedTrack original = translatedTrack();
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of(
                translationCorrection(1L, "seg-0-1000", "zh-Hans", "改后的译文")));

        TranslatedTrack result = overlay.applyToTranslatedTrack(TASK_ID, "zh-Hans", original);

        assertEquals("改后的译文", result.segments().get(0).translatedText());
        assertEquals("原文 A", result.segments().get(0).sourceText(), "只换译文，原文照旧");
        assertEquals("机器译文 B", original.segments().get(1).translatedText(), "入参不得被改");
    }

    @Test
    void ignoresTranslationOfAnotherLanguage() {
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of(
                translationCorrection(1L, "seg-0-1000", "en", "english text")));

        TranslatedTrack result = overlay.applyToTranslatedTrack(TASK_ID, "zh-Hans", translatedTrack());

        assertEquals("机器译文 A", result.segments().get(0).translatedText(), "别的语种的纠错不能串过来");
    }

    @Test
    void ignoresCorrectionsOfAnotherField() {
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of(
                translationCorrection(1L, "seg-0-1000", "zh-Hans", "改后的译文")));

        SubtitleTrack result = overlay.applyToCleanTrack(TASK_ID, cleanTrack());

        assertEquals("机器原文 A", result.segments().get(0).text(), "译文纠错不该动原文");
    }

    @Test
    void returnsInputWhenThereAreNoCorrections() {
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of());

        // 没有纠错时原样返回同一个 track 实例（不可变，无需复制）
        SubtitleTrack track = cleanTrack();
        assertSame(track, overlay.applyToCleanTrack(TASK_ID, track));
    }

    @Test
    void matchesCorrectionWhenSegmentIsStillPresent() {
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of(
                textCorrection(1L, "seg-0-1000", "用户改的原文")));

        CorrectionView view = overlay.matchCorrections(TASK_ID, cleanTrack()).get(0);

        assertTrue(view.matched());
        assertEquals("text", view.field(), "读模型里 field 是对外词形，不是枚举");
        assertFalse(view.sourceChanged(), "存下的源文本与当前机器文本一致");
    }

    @Test
    void reportsSourceChangedWhenMachineTextDiffers() {
        // 存下时是旧文本，当前机器文本已经变了：这条改动需要复核
        SubtitleCorrection changed = new SubtitleCorrection(1L, TASK_ID, "seg-0-1000",
                SubtitleCorrectionField.TEXT, "", "旧机器原文", "用户改的原文", null, null);
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of(changed));

        CorrectionView view = overlay.matchCorrections(TASK_ID, cleanTrack()).get(0);

        assertTrue(view.matched());
        assertTrue(view.sourceChanged());
    }

    @Test
    void reportsUnmatchedWhenSegmentDisappeared() {
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of(
                textCorrection(1L, "seg-9-9999", "旧条目的改动")));

        CorrectionView view = overlay.matchCorrections(TASK_ID, cleanTrack()).get(0);

        assertFalse(view.matched(), "当前字幕里没有这个 id：该纠错已失效");
        assertFalse(view.sourceChanged(), "没命中的条目谈不上源文本变了");
    }

    @Test
    void reportsUnmatchedWhenThereIsNoCleanTrack() {
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of(
                textCorrection(1L, "seg-0-1000", "用户改的原文")));

        CorrectionView view = overlay.matchCorrections(TASK_ID, new SubtitleTrack(null, List.of(), null)).get(0);

        assertFalse(view.matched());
    }

    @Test
    void appliesToEverySegmentSharingTheSameId() {
        // 同一 id 出现两次（切分理论上不该发生）：按设计全部命中，不加后缀
        SubtitleTrack duplicated = new SubtitleTrack("zh-Hans", List.of(
                new TranscriptionSegment(0, 1000, "第一条", "seg-0-1000"),
                new TranscriptionSegment(0, 1000, "第二条同名 id", "seg-0-1000")), null);
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of(
                textCorrection(1L, "seg-0-1000", "用户改的原文")));

        SubtitleTrack result = overlay.applyToCleanTrack(TASK_ID, duplicated);

        assertTrue(result.segments().stream().allMatch(segment -> "用户改的原文".equals(segment.text())),
                "重复 id 的每一条都要被覆盖");
    }

    // ---------- 夹具 ----------

    private static SubtitleCorrection textCorrection(long id, String segmentId, String value) {
        return new SubtitleCorrection(id, TASK_ID, segmentId, SubtitleCorrectionField.TEXT, "",
                "机器原文 A", value, null, null);
    }

    private static SubtitleCorrection translationCorrection(long id, String segmentId, String lang, String value) {
        return new SubtitleCorrection(id, TASK_ID, segmentId, SubtitleCorrectionField.TRANSLATION, lang,
                "原文 A", value, null, null);
    }

    private static SubtitleTrack cleanTrack() {
        return new SubtitleTrack("zh-Hans", List.of(
                new TranscriptionSegment(0, 1000, "机器原文 A", "seg-0-1000"),
                new TranscriptionSegment(1000, 2000, "机器原文 B", "seg-1000-2000")), null);
    }

    private static TranslatedTrack translatedTrack() {
        return new TranslatedTrack("zh-Hans", "zh-Hans", false, List.of(
                new TranslatedSegment(0, 1000, "原文 A", "机器译文 A", "seg-0-1000"),
                new TranslatedSegment(1000, 2000, "原文 B", "机器译文 B", "seg-1000-2000")));
    }
}
