package com.vidsage.application.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.application.exception.InvalidWorkDirectoryException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SubtitleWorkspaceFilePaths#of} 的纯单元测试：<b>不起 Spring 容器</b>，只造目录与文件名。
 *
 * <p>文件名在这里<b>写字面量</b>、不调 {@code SubtitleArtifactSpec.getFileName}——
 * 那等于拿被测对象自己当期望值。字面量同时钉住「产物名长什么样」这条对外契约。
 *
 * <p>本类会真的建文件，沿用 {@code NioWorkspaceDirectoryManagerTest} 的环境性规避：不清理临时目录。
 */
class SubtitleWorkspaceFilePathsTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    // ---------- 全产物齐 ----------

    @Test
    void scansSubtitleArtifactsAndGroupsTranslationsByLanguage() throws IOException {
        touch("original-segments.json", "original-subtitles.srt",
                "clean-segments.json", "clean-subtitles.srt", "context.json",
                "translated-segments-en.json", "translated-subtitles-en.srt", "terms-en.json",
                "translated-segments-ja.json", "translated-subtitles-ja.srt",
                "source.mp4", "source.info.json", "audio-16k1ch48k-t0.m4a");

        SubtitleWorkspaceFilePaths paths = SubtitleWorkspaceFilePaths.of(tempDir);

        assertEquals(tempDir.resolve("original-segments.json"),
                paths.originalSegmentsJsonFilePath().orElseThrow());
        assertEquals(tempDir.resolve("original-subtitles.srt"),
                paths.originalSrtFilePath().orElseThrow());
        assertEquals(tempDir.resolve("clean-segments.json"),
                paths.cleanSegmentsJsonFilePath().orElseThrow());
        assertEquals(tempDir.resolve("clean-subtitles.srt"),
                paths.cleanSrtFilePath().orElseThrow());
        assertEquals(tempDir.resolve("context.json"),
                paths.contextJsonFilePath().orElseThrow());
        assertEquals(tempDir.resolve("source.mp4"),
                paths.sourceMediaFilePath().orElseThrow());
        assertEquals(tempDir.resolve("source.info.json"),
                paths.sourceInfoJsonFilePath().orElseThrow());
        assertEquals(tempDir.resolve("audio-16k1ch48k-t0.m4a"),
                paths.audioFilePath().orElseThrow());

        // 语种按字典序（en 在 ja 前），且 ja 只有两件——「这个语种没有术语表」写在元素内部，
        // 不需要消费者去猜列表下标
        List<TranslationFilePaths> translations = paths.translationFilePaths();
        assertEquals(2, translations.size(), "两个语种各一条");

        TranslationFilePaths en = translations.get(0);
        assertEquals("en", en.langCode());
        assertEquals(tempDir.resolve("translated-segments-en.json"),
                en.translatedSegmentsJsonFilePath().orElseThrow());
        assertEquals(tempDir.resolve("translated-subtitles-en.srt"),
                en.translatedSrtFilePath().orElseThrow());
        assertEquals(tempDir.resolve("terms-en.json"),
                en.translatedTermsJsonFilePath().orElseThrow());

        TranslationFilePaths ja = translations.get(1);
        assertEquals("ja", ja.langCode());
        assertTrue(ja.translatedTermsJsonFilePath().isEmpty(), "ja 没有查证过术语");
        assertEquals(tempDir.resolve("translated-subtitles-ja.srt"),
                ja.translatedSrtFilePath().orElseThrow());
    }

    // ---------- 语种并集 ----------

    @Test
    void keepsLanguageThatOnlyHasTerms() throws IOException {
        // 术语查证跑在翻译之前；翻译中途失败会留下「有 terms、没 segments」的语种。
        // 只看 segments 的语种集合会整个漏掉它，下次重跑白查一遍术语
        touch("terms-zh-hant.json");

        List<TranslationFilePaths> translations =
                SubtitleWorkspaceFilePaths.of(tempDir).translationFilePaths();

        assertEquals(1, translations.size());
        assertEquals("zh-hant", translations.get(0).langCode());
        assertTrue(translations.get(0).translatedSegmentsJsonFilePath().isEmpty());
        assertEquals(tempDir.resolve("terms-zh-hant.json"),
                translations.get(0).translatedTermsJsonFilePath().orElseThrow());
    }

    // ---------- 媒体三项 ----------

    @Test
    void recognizesSourceMediaWhoseExtensionIsNotMp4() throws IOException {
        // yt-dlp 的输出模板是 source.%(ext)s，扩展名由站点给（上传那条路才恒定 source.mp4）。
        // 判据必须是「source. 打头且不是 info json」——写成 equals("source.mp4") 会静默漏掉 m4a/webm
        touch("source.webm", "source.info.json");

        SubtitleWorkspaceFilePaths paths = SubtitleWorkspaceFilePaths.of(tempDir);

        assertEquals(tempDir.resolve("source.webm"), paths.sourceMediaFilePath().orElseThrow());
        assertEquals(tempDir.resolve("source.info.json"), paths.sourceInfoJsonFilePath().orElseThrow());
    }

    @Test
    void takesTheFirstMatchByFileNameOrderWhenSeveralShareAPrefix() throws IOException {
        // 重跑残留：同一前缀下可能躺着多个（换源重下、换规格重抽音频）。
        // 取字典序第一个——确定性优先于猜「哪个才对」；不排序的话取哪个由文件系统定
        touch("source.webm", "source.mp4",
                "audio-8k1ch-t0.wav", "audio-16k1ch48k-t0.m4a");

        SubtitleWorkspaceFilePaths paths = SubtitleWorkspaceFilePaths.of(tempDir);

        assertEquals(tempDir.resolve("source.mp4"), paths.sourceMediaFilePath().orElseThrow(),
                "source.mp4 字典序在 source.webm 前");
        assertEquals(tempDir.resolve("audio-16k1ch48k-t0.m4a"), paths.audioFilePath().orElseThrow(),
                "audio-16k… 字典序在 audio-8k… 前");
    }

    // ---------- 误认与缺席 ----------

    @Test
    void ignoresNamesThatOnlyLookLikeArtifacts() throws IOException {
        // 备份文件与「少了语种段」的名字都不是产物：后者若被认下，会解出一个空语种
        touch("original-segments.json.bak", "translated-segments.json", "clean-subtitles.srt.tmp");

        SubtitleWorkspaceFilePaths paths = SubtitleWorkspaceFilePaths.of(tempDir);

        assertTrue(paths.originalSegmentsJsonFilePath().isEmpty());
        assertTrue(paths.cleanSrtFilePath().isEmpty());
        assertTrue(paths.translationFilePaths().isEmpty(), "不产出来路不明的语种");
    }

    @Test
    void ignoresSubdirectories() throws IOException {
        // 只认普通文件：同名目录不该被当成产物（工作区里可能有人手建的目录）
        Files.createDirectory(tempDir.resolve("original-segments.json"));

        assertTrue(SubtitleWorkspaceFilePaths.of(tempDir).originalSegmentsJsonFilePath().isEmpty());
    }

    @Test
    void returnsEmptySnapshotForEmptyWorkspace() {
        SubtitleWorkspaceFilePaths paths = SubtitleWorkspaceFilePaths.of(tempDir);

        assertTrue(paths.originalSegmentsJsonFilePath().isEmpty());
        assertTrue(paths.originalSrtFilePath().isEmpty());
        assertTrue(paths.cleanSegmentsJsonFilePath().isEmpty());
        assertTrue(paths.cleanSrtFilePath().isEmpty());
        assertTrue(paths.contextJsonFilePath().isEmpty());
        assertTrue(paths.sourceMediaFilePath().isEmpty());
        assertTrue(paths.sourceInfoJsonFilePath().isEmpty());
        assertTrue(paths.audioFilePath().isEmpty());
        assertTrue(paths.translationFilePaths().isEmpty());
    }

    @Test
    void returnsEmptySnapshotWhenDirectoryDoesNotExist() {
        // 「工作区还没建」是正常状态（管线一路懒建），不是错误——下游据空清单判重跑
        SubtitleWorkspaceFilePaths paths = SubtitleWorkspaceFilePaths.of(tempDir.resolve("not-created-yet"));

        assertTrue(paths.originalSegmentsJsonFilePath().isEmpty());
        assertTrue(paths.translationFilePaths().isEmpty());
    }

    @Test
    void rejectsNullWorkDirectory() {
        // 唯一抛的情形：调用方传参错。其余缺席一律空清单
        assertThrows(InvalidWorkDirectoryException.class, () -> SubtitleWorkspaceFilePaths.of(null));
    }

    // 注：「目录在、但读不出来 → UncheckedIOException」这条没测：Windows 上造一个不可读目录不可靠
    // （ACL 对同一用户常失效）。实现靠 Files.isDirectory 守卫 + catch IOException，逻辑在 §4.1 可核。

    private void touch(String... fileNames) throws IOException {
        for (String fileName : fileNames) {
            Files.writeString(tempDir.resolve(fileName), "x");
        }
    }
}
