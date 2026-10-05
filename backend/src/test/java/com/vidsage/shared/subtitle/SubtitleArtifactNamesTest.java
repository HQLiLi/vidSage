package com.vidsage.shared.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vidsage.domain.subtitle.SubtitleRoute;
import org.junit.jupiter.api.Test;

/**
 * SubtitleArtifactNames 的纯单测：直接断言产物文件名字符串，不起 Spring 容器。
 *
 * <p><b>这些断言是「文件名没变」的验收证据</b>：工作目录按媒体内容寻址，名字改一个字符，
 * 存量产物就会从「已完成」打回「未完成」。不要为了让断言好看而改这里的期望值。
 */
class SubtitleArtifactNamesTest {

    @Test
    void derivesKebabTokenFromAllFourRouteValues() {
        assertEquals("platform-manual", SubtitleArtifactNames.routeToken(SubtitleRoute.PLATFORM_MANUAL));
        assertEquals("platform-auto", SubtitleArtifactNames.routeToken(SubtitleRoute.PLATFORM_AUTO));
        assertEquals("asr-local", SubtitleArtifactNames.routeToken(SubtitleRoute.ASR_LOCAL));
        assertEquals("asr-cloud", SubtitleArtifactNames.routeToken(SubtitleRoute.ASR_CLOUD));
    }

    @Test
    void artifactFileNamesCarryRouteFingerprint() {
        assertEquals("subtitles-asr-cloud.srt", SubtitleArtifactNames.srtFileName(SubtitleRoute.ASR_CLOUD));
        assertEquals("segments-asr-cloud.json", SubtitleArtifactNames.segmentsFileName(SubtitleRoute.ASR_CLOUD));
        assertEquals("subtitles-asr-local.srt", SubtitleArtifactNames.srtFileName(SubtitleRoute.ASR_LOCAL));
        assertEquals("segments-asr-local.json", SubtitleArtifactNames.segmentsFileName(SubtitleRoute.ASR_LOCAL));
    }

    @Test
    void cleanArtifactFileNamesCarryRouteFingerprint() {
        assertEquals("subtitles-asr-cloud-clean.srt", SubtitleArtifactNames.cleanSrtFileName(SubtitleRoute.ASR_CLOUD));
        assertEquals("segments-asr-cloud-clean.json",
                SubtitleArtifactNames.cleanSegmentsFileName(SubtitleRoute.ASR_CLOUD));
    }

    @Test
    void contextFileNameCarriesRouteFingerprintButNoTargetLanguage() {
        assertEquals("context-asr-cloud.json", SubtitleArtifactNames.contextFileName(SubtitleRoute.ASR_CLOUD));
        assertEquals("context-asr-local.json", SubtitleArtifactNames.contextFileName(SubtitleRoute.ASR_LOCAL));
    }

    @Test
    void translateArtifactFileNamesCarryRouteAndTargetLanguageFingerprint() {
        assertEquals("segments-asr-cloud-translate-zh.json",
                SubtitleArtifactNames.translateSegmentsFileName(SubtitleRoute.ASR_CLOUD, "zh"));
        assertEquals("subtitles-asr-cloud-translate-zh.srt",
                SubtitleArtifactNames.translateSrtFileName(SubtitleRoute.ASR_CLOUD, "zh"));
        assertEquals("subtitles-asr-local-translate-en.srt",
                SubtitleArtifactNames.translateSrtFileName(SubtitleRoute.ASR_LOCAL, "en"));
    }

    @Test
    void normalizesTargetLanguageCaseAndSurroundingWhitespace() {
        // ZH /  zh  必须落到同一个文件名，否则大小写一变就静默产出第二份产物、绕开断点
        assertEquals("segments-asr-cloud-translate-zh.json",
                SubtitleArtifactNames.translateSegmentsFileName(SubtitleRoute.ASR_CLOUD, "ZH"));
        assertEquals("segments-asr-cloud-translate-zh.json",
                SubtitleArtifactNames.translateSegmentsFileName(SubtitleRoute.ASR_CLOUD, "  zh  "));
    }

    @Test
    void rejectsBlankTargetLanguage() {
        // 用 ISE 而非 IAE：语种来自配置不是请求体，属「环境不对」；抛 IAE 会被映射成 400，
        // 而调用方的请求本身完全合法（见 error-handling 的「非法值是谁产生的」判据）
        assertThrows(IllegalStateException.class,
                () -> SubtitleArtifactNames.translateSegmentsFileName(SubtitleRoute.ASR_CLOUD, "  "));
        assertThrows(IllegalStateException.class,
                () -> SubtitleArtifactNames.translateSrtFileName(SubtitleRoute.ASR_CLOUD, null));
    }
}
