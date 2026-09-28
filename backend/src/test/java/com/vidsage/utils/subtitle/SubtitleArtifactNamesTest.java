package com.vidsage.utils.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vidsage.model.subtitle.SubtitleRoute;
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
}
