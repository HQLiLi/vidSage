package com.vidsage.domain.subtitle.model.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * SubtitleArtifactSpec 的纯单测：直接断言产物文件名字符串，不起 Spring 容器。
 *
 * <p><b>这些断言是「文件名没变」的验收证据</b>：工作目录按媒体内容寻址，名字改一个字符，
 * 存量产物就会从「已完成」打回「未完成」。不要为了让断言好看而改这里的期望值。
 */
class SubtitleArtifactSpecTest {

    @Test
    void routeFingerprintCoversAllFourRouteValues() {
        // token 派生现在是私有的，只能经产物名间接验证——四条路线都要覆盖，别只测常用的两条
        assertEquals("segments-platform-manual.json",
                SubtitleArtifactSpec.SEGMENTS_RAW.fileName(SubtitleRoute.PLATFORM_MANUAL, null));
        assertEquals("segments-platform-auto.json",
                SubtitleArtifactSpec.SEGMENTS_RAW.fileName(SubtitleRoute.PLATFORM_AUTO, null));
        assertEquals("segments-asr-local.json",
                SubtitleArtifactSpec.SEGMENTS_RAW.fileName(SubtitleRoute.ASR_LOCAL, null));
        assertEquals("segments-asr-cloud.json",
                SubtitleArtifactSpec.SEGMENTS_RAW.fileName(SubtitleRoute.ASR_CLOUD, null));
    }

    @Test
    void rawArtifactNamesCarryRouteFingerprint() {
        assertEquals("subtitles-asr-cloud.srt",
                SubtitleArtifactSpec.SRT_RAW.fileName(SubtitleRoute.ASR_CLOUD, null));
        assertEquals("subtitles-asr-local.srt",
                SubtitleArtifactSpec.SRT_RAW.fileName(SubtitleRoute.ASR_LOCAL, null));
    }

    @Test
    void cleanArtifactNamesCarryRouteFingerprint() {
        assertEquals("subtitles-asr-cloud-clean.srt",
                SubtitleArtifactSpec.SRT_CLEAN.fileName(SubtitleRoute.ASR_CLOUD, null));
        assertEquals("segments-asr-cloud-clean.json",
                SubtitleArtifactSpec.SEGMENTS_CLEAN.fileName(SubtitleRoute.ASR_CLOUD, null));
    }

    @Test
    void contextFileNameCarriesRouteFingerprintButNoTargetLanguage() {
        // 故意不带语种指纹：上下文就三样（主题 / 术语 / 源语种），与译成哪种语言无关，
        // 带指纹反而会让换目标语种时白重跑一次摘要
        assertEquals("context-asr-cloud.json",
                SubtitleArtifactSpec.CONTEXT.fileName(SubtitleRoute.ASR_CLOUD, null));
        assertEquals("context-asr-local.json",
                SubtitleArtifactSpec.CONTEXT.fileName(SubtitleRoute.ASR_LOCAL, null));
    }

    @Test
    void translateArtifactFileNamesCarryRouteAndTargetLanguageFingerprint() {
        assertEquals("segments-asr-cloud-translate-zh.json",
                SubtitleArtifactSpec.SEGMENTS_TRANSLATED.fileName(SubtitleRoute.ASR_CLOUD, "zh"));
        assertEquals("subtitles-asr-cloud-translate-zh.srt",
                SubtitleArtifactSpec.SRT_TRANSLATED.fileName(SubtitleRoute.ASR_CLOUD, "zh"));
        assertEquals("subtitles-asr-local-translate-en.srt",
                SubtitleArtifactSpec.SRT_TRANSLATED.fileName(SubtitleRoute.ASR_LOCAL, "en"));
    }

    @Test
    void termsFileNameCarriesRouteAndTargetLanguageFingerprint() {
        // 换目标语种必须另查一份译名，绝不能拿上一份语种的凑数
        assertEquals("terms-asr-cloud-en.json",
                SubtitleArtifactSpec.TERMS.fileName(SubtitleRoute.ASR_CLOUD, "en"));
        assertEquals("terms-asr-local-zh.json",
                SubtitleArtifactSpec.TERMS.fileName(SubtitleRoute.ASR_LOCAL, "zh"));
    }

    @Test
    void normalizesTargetLanguageCaseAndSurroundingWhitespace() {
        // ZH /  zh  必须落到同一个文件名，否则大小写一变就静默产出第二份产物、绕开断点判据。
        // 归一化口径单源在 Bcp47Language.resolveTargetLanguage，登记表不复制它，只是调它。
        assertEquals("segments-asr-cloud-translate-zh.json",
                SubtitleArtifactSpec.SEGMENTS_TRANSLATED.fileName(SubtitleRoute.ASR_CLOUD, "ZH"));
        assertEquals("segments-asr-cloud-translate-zh.json",
                SubtitleArtifactSpec.SEGMENTS_TRANSLATED.fileName(SubtitleRoute.ASR_CLOUD, "  zh  "));
    }

    @Test
    void rejectsBlankTargetLanguageForFingerprintedArtifacts() {
        // 用 ISE 而非 IAE：语种来自配置不是请求体，属「环境不对」；抛 IAE 会被映射成 400，
        // 而调用方的请求本身完全合法（见 error-handling 的「非法值是谁产生的」判据）
        assertThrows(IllegalStateException.class,
                () -> SubtitleArtifactSpec.SEGMENTS_TRANSLATED.fileName(SubtitleRoute.ASR_CLOUD, "  "));
        assertThrows(IllegalStateException.class,
                () -> SubtitleArtifactSpec.SRT_TRANSLATED.fileName(SubtitleRoute.ASR_CLOUD, null));
        assertThrows(IllegalStateException.class,
                () -> SubtitleArtifactSpec.TERMS.fileName(SubtitleRoute.ASR_CLOUD, null));
    }

    @Test
    void artifactsWithoutLanguageFingerprintIgnoreLanguageArgument() {
        // 不含 {lang} 的模板必须容忍 null——非语种产物就是这么调的（fileName(route, null)）；
        // 若实现里无条件调归一化，这几条会抛，非语种调用点全部炸掉
        assertEquals("segments-asr-cloud.json",
                SubtitleArtifactSpec.SEGMENTS_RAW.fileName(SubtitleRoute.ASR_CLOUD, null));
        assertEquals("subtitles-asr-cloud.srt",
                SubtitleArtifactSpec.SRT_RAW.fileName(SubtitleRoute.ASR_CLOUD, null));
        assertEquals("context-asr-cloud.json",
                SubtitleArtifactSpec.CONTEXT.fileName(SubtitleRoute.ASR_CLOUD, null));
    }

    @Test
    void artifactNamesRoundTripThroughMatches() {
        // 钉住「fileName 产出的名字必须能被自己的 matches 认出来」这个往返不变量——
        // 规则里少一组括号（如路由交替没套括号组），matches 就会对任何产物名恒 false，
        // artifacts 清单恒空、重新生成删不掉译文产物。
        for (SubtitleRoute route : SubtitleRoute.values()) {
            for (SubtitleArtifactSpec spec : SubtitleArtifactSpec.values()) {
                String name = spec.fileName(route, spec == SubtitleArtifactSpec.CONTEXT ? null : "en");
                assertTrue(spec.matches(name), spec + " 应认得出自己生成的文件名：" + name);
            }
        }
        // 反例：带 -clean 后缀的名字不许被 raw 版认去，垃圾名更不许
        assertFalse(SubtitleArtifactSpec.SEGMENTS_RAW.matches("segments-asr-local-clean.json"));
        assertFalse(SubtitleArtifactSpec.SEGMENTS_RAW.matches("garbage.txt"));
    }

    @Test
    void downloadabilityMatchesThePreviousSuffixWhitelist() {
        // 判据从「后缀白名单」换成「声明的属性」后，对外清单必须与改造前逐项一致：
        // *.srt 与 segments-*.json 可见，context- / terms- 等内部缓存不可见
        assertTrue(SubtitleArtifactSpec.SEGMENTS_RAW.isDownloadable());
        assertTrue(SubtitleArtifactSpec.SRT_RAW.isDownloadable());
        assertTrue(SubtitleArtifactSpec.SEGMENTS_CLEAN.isDownloadable());
        assertTrue(SubtitleArtifactSpec.SRT_CLEAN.isDownloadable());
        assertTrue(SubtitleArtifactSpec.SEGMENTS_TRANSLATED.isDownloadable());
        assertTrue(SubtitleArtifactSpec.SRT_TRANSLATED.isDownloadable());
        assertFalse(SubtitleArtifactSpec.CONTEXT.isDownloadable());
        assertFalse(SubtitleArtifactSpec.TERMS.isDownloadable());
    }

    @Test
    void checkpointFlagMarksTheJsonCarriersOnly() {
        // SRT 是由 JSON 渲染的派生视图，不参与幂等判定；JSON 才是断点判据的载体
        assertTrue(SubtitleArtifactSpec.SEGMENTS_RAW.isCheckpoint());
        assertFalse(SubtitleArtifactSpec.SRT_RAW.isCheckpoint());
        assertTrue(SubtitleArtifactSpec.SEGMENTS_CLEAN.isCheckpoint());
        assertFalse(SubtitleArtifactSpec.SRT_CLEAN.isCheckpoint());
        assertTrue(SubtitleArtifactSpec.SEGMENTS_TRANSLATED.isCheckpoint());
        assertFalse(SubtitleArtifactSpec.SRT_TRANSLATED.isCheckpoint());
        assertTrue(SubtitleArtifactSpec.CONTEXT.isCheckpoint());
        assertTrue(SubtitleArtifactSpec.TERMS.isCheckpoint());
    }
}
