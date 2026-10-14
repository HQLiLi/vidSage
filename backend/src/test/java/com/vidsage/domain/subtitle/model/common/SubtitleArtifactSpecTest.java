package com.vidsage.domain.subtitle.model.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * SubtitleArtifactSpec 的纯单测：直接断言产物文件名字符串，不起 Spring 容器。
 *
 * <p><b>这些断言是「文件名没变」的验收证据</b>：工作目录按媒体内容寻址，名字改一个字符，
 * 存量产物就会从「已完成」打回「未完成」。不要为了让断言好看而改这里的期望值。
 *
 * <p>另一组钉的是<b>反解</b>（{@link SubtitleArtifactSpec#getLanguage}）——工作区扫描靠它
 * 认出「盘上有哪些语种」，它是 {@code getFileName} 的逆运算，两者必须同源。
 */
class SubtitleArtifactSpecTest {

    /**
     * 8 个产物的确切名字，逐条钉住。
     *
     * <p>语种一律用 {@code zh-hant}：带区域码，顺便证明连字符也在语种段里（正则那格是
     * {@code [a-z0-9-]+}，不含点与下划线）。
     */
    @Test
    void fileNameOfEveryArtifact() {
        assertEquals("original-segments.json",
                SubtitleArtifactSpec.ORIGINAL_SEGMENTS.getFileName(null));
        assertEquals("original-subtitles.srt",
                SubtitleArtifactSpec.ORIGINAL_SRT.getFileName(null));
        assertEquals("clean-segments.json",
                SubtitleArtifactSpec.CLEAN_SEGMENTS.getFileName(null));
        assertEquals("clean-subtitles.srt",
                SubtitleArtifactSpec.CLEAN_SRT.getFileName(null));
        assertEquals("translated-segments-zh-hant.json",
                SubtitleArtifactSpec.TRANSLATED_SEGMENTS.getFileName("zh-hant"));
        assertEquals("translated-subtitles-zh-hant.srt",
                SubtitleArtifactSpec.TRANSLATED_SRT.getFileName("zh-hant"));
        assertEquals("context.json",
                SubtitleArtifactSpec.CONTEXT.getFileName(null));
        assertEquals("terms-zh-hant.json",
                SubtitleArtifactSpec.TERMS.getFileName("zh-hant"));
    }

    @Test
    void normalizesTargetLanguageCaseAndSurroundingWhitespace() {
        // ZH /  zh  必须落到同一个文件名，否则大小写一变就静默产出第二份产物、绕开断点判据。
        // 归一化口径单源在 Bcp47LanguagePolicy.resolveTargetLanguage，登记表不复制它，只是调它。
        assertEquals("translated-segments-zh.json",
                SubtitleArtifactSpec.TRANSLATED_SEGMENTS.getFileName("ZH"));
        assertEquals("translated-segments-zh.json",
                SubtitleArtifactSpec.TRANSLATED_SEGMENTS.getFileName("  zh  "));
    }

    @Test
    void rejectsBlankTargetLanguageForFingerprintedArtifacts() {
        // 用 ISE 而非 IAE：语种来自配置不是请求体，属「环境不对」；抛 IAE 会被映射成 400，
        // 而调用方的请求本身完全合法（见 error-handling 的「非法值是谁产生的」判据）
        assertThrows(IllegalStateException.class,
                () -> SubtitleArtifactSpec.TRANSLATED_SEGMENTS.getFileName("  "));
        assertThrows(IllegalStateException.class,
                () -> SubtitleArtifactSpec.TRANSLATED_SRT.getFileName(null));
        assertThrows(IllegalStateException.class,
                () -> SubtitleArtifactSpec.TERMS.getFileName(null));
    }

    @Test
    void artifactsWithoutLanguageFingerprintIgnoreLanguageArgument() {
        // 不含 {lang} 的模板必须容忍 null——非语种产物就是这么调的（getFileName(null)）；
        // 若实现里无条件调归一化，这几条会抛，非语种调用点全部炸掉
        assertEquals("original-segments.json",
                SubtitleArtifactSpec.ORIGINAL_SEGMENTS.getFileName(null));
        assertEquals("original-subtitles.srt",
                SubtitleArtifactSpec.ORIGINAL_SRT.getFileName(null));
        assertEquals("context.json",
                SubtitleArtifactSpec.CONTEXT.getFileName(null));
    }

    @Test
    void artifactNamesRoundTripThroughMatches() {
        // 钉住「getFileName 产出的名字必须能被自己的 matches 认出来」这个往返不变量。
        // 规则里少一组括号就会在这里现形：{lang} 那格的正则若只写右括号、没有配对的左括号，
        // Pattern.compile 在枚举 <clinit> 里就抛 PatternSyntaxException（整个类不可用）；
        // 若干脆不写捕获组，matches 照常 true，但下面的 getLanguage 往返会全 null。
        for (SubtitleArtifactSpec spec : SubtitleArtifactSpec.values()) {
            boolean hasLanguage = spec == SubtitleArtifactSpec.TRANSLATED_SEGMENTS
                    || spec == SubtitleArtifactSpec.TRANSLATED_SRT
                    || spec == SubtitleArtifactSpec.TERMS;
            String name = spec.getFileName(hasLanguage ? "en" : null);
            assertTrue(spec.matches(name), spec + " 应认得出自己生成的文件名：" + name);
        }
        // 反例：别人生成的名字不许被认去，垃圾名更不许
        assertFalse(SubtitleArtifactSpec.CLEAN_SEGMENTS.matches("original-segments.json"));
        assertFalse(SubtitleArtifactSpec.CLEAN_SEGMENTS.matches("clean-segments.json.bak"));
        assertFalse(SubtitleArtifactSpec.CLEAN_SEGMENTS.matches("garbage.txt"));
    }

    @Test
    void getLanguageReversesGetFileName() {
        // 反解是 getFileName 的逆运算，两者同源于模板那一份正则——正着生成再倒着解回来，
        // 必须得到同一个语种，否则工作区扫描会把「文件名的并集」当成「语种的并集」
        // （实测过：漏写捕获组时 langCode 会变成整个文件名）。
        for (String code : new String[]{"en", "ja", "zh-hans", "zh-hant"}) {
            assertEquals(code, SubtitleArtifactSpec.TRANSLATED_SEGMENTS
                    .getLanguage(SubtitleArtifactSpec.TRANSLATED_SEGMENTS.getFileName(code)));
            assertEquals(code, SubtitleArtifactSpec.TRANSLATED_SRT
                    .getLanguage(SubtitleArtifactSpec.TRANSLATED_SRT.getFileName(code)));
            assertEquals(code, SubtitleArtifactSpec.TERMS
                    .getLanguage(SubtitleArtifactSpec.TERMS.getFileName(code)));
        }
    }

    @Test
    void getLanguageReturnsNullWhenThereIsNothingToReverse() {
        // 三种解不出来的情形，都要返回 null（不是抛、不是空串）——扫描器靠 null 判断「这条不是语种产物」
        assertNull(SubtitleArtifactSpec.TRANSLATED_SEGMENTS.getLanguage(null), "文件名为空");
        assertNull(SubtitleArtifactSpec.TRANSLATED_SEGMENTS.getLanguage("translated-segments.json"),
                "缺语种段");
        assertNull(SubtitleArtifactSpec.TRANSLATED_SEGMENTS.getLanguage("translated-segments-en.json.bak"),
                "多出后缀，整名不匹配");
        assertNull(SubtitleArtifactSpec.TERMS.getLanguage("translated-segments-en.json"),
                "是别的产物的名字");
        // 模板本身不带 {lang} 的（原始 / 清洗 / 上下文）恒为 null
        for (SubtitleArtifactSpec spec : SubtitleArtifactSpec.values()) {
            boolean hasLanguage = spec == SubtitleArtifactSpec.TRANSLATED_SEGMENTS
                    || spec == SubtitleArtifactSpec.TRANSLATED_SRT
                    || spec == SubtitleArtifactSpec.TERMS;
            if (!hasLanguage) {
                assertNull(spec.getLanguage(spec.getFileName(null)), spec + " 没有语种可解");
            }
        }
    }

    @Test
    void downloadabilityFlagMarksTheOutwardFacingViews() {
        // 判据是「声明的属性」而不是后缀白名单：用户可下的只有 SRT 与 segments JSON，
        // context / terms 是内部件，不进 artifacts 清单
        assertTrue(SubtitleArtifactSpec.ORIGINAL_SEGMENTS.isDownloadable());
        assertTrue(SubtitleArtifactSpec.ORIGINAL_SRT.isDownloadable());
        assertTrue(SubtitleArtifactSpec.CLEAN_SEGMENTS.isDownloadable());
        assertTrue(SubtitleArtifactSpec.CLEAN_SRT.isDownloadable());
        assertTrue(SubtitleArtifactSpec.TRANSLATED_SEGMENTS.isDownloadable());
        assertTrue(SubtitleArtifactSpec.TRANSLATED_SRT.isDownloadable());
        assertFalse(SubtitleArtifactSpec.CONTEXT.isDownloadable());
        assertFalse(SubtitleArtifactSpec.TERMS.isDownloadable());
    }

    @Test
    void checkpointFlagMarksTheJsonCarriersOnly() {
        // SRT 是由 JSON 渲染的派生视图，不参与幂等判定；JSON 才是断点判据的载体
        assertTrue(SubtitleArtifactSpec.ORIGINAL_SEGMENTS.isCheckpoint());
        assertFalse(SubtitleArtifactSpec.ORIGINAL_SRT.isCheckpoint());
        assertTrue(SubtitleArtifactSpec.CLEAN_SEGMENTS.isCheckpoint());
        assertFalse(SubtitleArtifactSpec.CLEAN_SRT.isCheckpoint());
        assertTrue(SubtitleArtifactSpec.TRANSLATED_SEGMENTS.isCheckpoint());
        assertFalse(SubtitleArtifactSpec.TRANSLATED_SRT.isCheckpoint());
        assertTrue(SubtitleArtifactSpec.CONTEXT.isCheckpoint());
        assertTrue(SubtitleArtifactSpec.TERMS.isCheckpoint());
    }
}
