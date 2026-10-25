package com.vidsage.application.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
import com.vidsage.domain.subtitle.model.translation.TranslatedTrack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link SubtitleTrackReader} 的单元测试：产物门面 mock，工作区用临时目录里的真实文件。
 *
 * <p>钉住两件事：一是<b>读旧产物不报错</b>（没有 id 的 segments JSON 读出来每条都补了 id）；
 * 二是读不出来时一律当「没有」返回空、<b>不抛 500</b>——调用方要靠「有没有」决定怎么提示用户。
 */
class SubtitleTrackReaderTest {

    // 会真写文件，Windows 上 @TempDir 清理偶发失败，按 spec 不清理
    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private static final long TASK_ID = 7L;

    private static final String CLEAN_SEGMENTS = "clean-segments.json";

    private static final String TRANSLATED_SEGMENTS = "translated-segments-zh-hans.json";

    private final SubtitleArtifactStore artifactStore = mock(SubtitleArtifactStore.class);

    private final SubtitleTrackReader reader = new SubtitleTrackReader(artifactStore, new ObjectMapper());

    @Test
    void readsLegacyCleanTrackAndFillsMissingIds() throws IOException {
        write(CLEAN_SEGMENTS, """
                {"language":"zh-Hans",
                 "segments":[{"beginTimeMs":0,"endTimeMs":1000,"text":"旧文本"},
                             {"beginTimeMs":1000,"endTimeMs":2000,"text":"旧文本二"}],
                 "keywordsFingerprint":"fp-1"}
                """);
        stubCleanArtifact(CLEAN_SEGMENTS);

        var track = reader.readCleanTrack(TASK_ID, workDir).orElseThrow();

        assertEquals("zh-Hans", track.language());
        assertEquals("fp-1", track.keywordsFingerprint());
        assertEquals("seg-0-1000", track.segments().getFirst().id(), "旧产物没有 id 字段，读出来要按时间戳补上");
        assertEquals("seg-1000-2000", track.segments().getLast().id());
    }

    @Test
    void readsTranslatedTrackByLanguage() throws IOException {
        write(TRANSLATED_SEGMENTS, """
                {"sourceLanguage":"zh-Hans","targetLanguage":"zh-Hans","skipped":false,
                 "segments":[{"beginTimeMs":0,"endTimeMs":1000,"sourceText":"旧文本","translatedText":"旧译文"}]}
                """);
        when(artifactStore.findIntactArtifact(TASK_ID, ArtifactType.TRANSLATED, "zh-Hans", workDir))
                .thenReturn(Optional.of(artifact(ArtifactType.TRANSLATED, "zh-Hans", TRANSLATED_SEGMENTS)));

        TranslatedTrack track = reader.readTranslatedTrack(TASK_ID, "zh-Hans", workDir).orElseThrow();

        assertEquals("旧译文", track.segments().getFirst().translatedText());
        assertEquals("seg-0-1000", track.segments().getFirst().id());
    }

    @Test
    void returnsEmptyWhenThereIsNoIntactArtifact() {
        when(artifactStore.findIntactArtifact(TASK_ID, ArtifactType.CLEANED, "", workDir))
                .thenReturn(Optional.empty());

        assertTrue(reader.readCleanTrack(TASK_ID, workDir).isEmpty(), "还没产出不是错误，按没有字幕处理");
    }

    @Test
    void returnsEmptyWhenSegmentsFileIsNotRegistered() {
        // 产物行是 READY，但 segments 文件位没登记：自相矛盾，也只能当没有
        stubCleanArtifact(null);

        assertTrue(reader.readCleanTrack(TASK_ID, workDir).isEmpty());
    }

    @Test
    void returnsEmptyWhenSegmentsFileIsGone() {
        stubCleanArtifact(CLEAN_SEGMENTS);

        assertTrue(reader.readCleanTrack(TASK_ID, workDir).isEmpty(), "登记了文件但磁盘上没有，不抛 500");
    }

    @Test
    void returnsEmptyWhenSegmentsFileIsEmpty() throws IOException {
        write(CLEAN_SEGMENTS, "");
        stubCleanArtifact(CLEAN_SEGMENTS);

        assertTrue(reader.readCleanTrack(TASK_ID, workDir).isEmpty(), "0 字节文件解析不了，不抛 500");
    }

    @Test
    void returnsEmptyWhenJsonIsBroken() throws IOException {
        write(CLEAN_SEGMENTS, "{\"language\":");
        stubCleanArtifact(CLEAN_SEGMENTS);

        assertTrue(reader.readCleanTrack(TASK_ID, workDir).isEmpty(), "JSON 坏了按没有处理，不抛 500");
    }

    // ---------- 夹具 ----------

    private void stubCleanArtifact(String segmentsFileName) {
        when(artifactStore.findIntactArtifact(TASK_ID, ArtifactType.CLEANED, "", workDir))
                .thenReturn(Optional.of(artifact(ArtifactType.CLEANED, "", segmentsFileName)));
    }

    private void write(String fileName, String content) throws IOException {
        Files.writeString(workDir.resolve(fileName), content, StandardCharsets.UTF_8);
    }

    private static SubtitleArtifact artifact(ArtifactType type, String lang, String segmentsFileName) {
        return SubtitleArtifact.restore(1L, TASK_ID, 1L, type, lang, 1, SubtitleArtifactStatus.READY,
                null, null, segmentsFileName, "subtitles.srt", null, null, "a", "b", null, null,
                LocalDateTime.of(2026, 10, 25, 10, 0));
    }
}
