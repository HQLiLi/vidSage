package com.vidsage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.config.SubtitleTranslateProperties;
import com.vidsage.model.asr.TranscriptionSegment;
import com.vidsage.model.media.MediaInput;
import com.vidsage.model.subtitle.SubtitleContext;
import com.vidsage.model.subtitle.SubtitleRoute;
import com.vidsage.model.subtitle.SubtitleTrack;
import com.vidsage.model.subtitle.TranslatedSegment;
import com.vidsage.model.subtitle.TranslatedSubtitle;
import com.vidsage.model.subtitle.TranslatedTrack;
import com.vidsage.provider.asr.ASRProvider;
import com.vidsage.provider.llm.SubtitleContextSummarizer;
import com.vidsage.provider.llm.SubtitleTranslator;
import com.vidsage.utils.subtitle.SubtitleArtifactNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * SubtitleTranslationService 的纯单元测试：<b>不起 Spring 容器</b>。
 *
 * <p>只测<b>编排</b>：定位产物 → 断点复用 → 上下文缓存 → 源语种跳过判定 → 交给 {@link SubtitleTranslator}
 * → 写 -translate 产物。摘要器与翻译器都是 mock。
 *
 * <p><b>翻译算法本身（分批、校验、重试、回退原文）不在这里测</b>——它的覆盖在
 * {@code SubtitleTranslatorTest}：真 ChatClient + 桩 ChatModel。
 */
class SubtitleTranslationServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SubtitleTranslateProperties PROPS =
            new SubtitleTranslateProperties("zh", 12, 400, 3, 20000);

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final ASRProvider asrProvider = mock(ASRProvider.class);
    private final SubtitleContextSummarizer contextSummarizer = mock(SubtitleContextSummarizer.class);
    private final SubtitleTranslator subtitleTranslator = mock(SubtitleTranslator.class);

    /** 翻译器被调了几次——「跳过 / 断点复用后不再跑」这类断言靠它。 */
    private final AtomicInteger translatorCalls = new AtomicInteger();

    /**
     * 翻译器的桩：给每条加「译文:」前缀，便于区分「真翻译」与「跳过时照抄原文」；调用次数记进计数器。
     * 返回值里的重试 / 未翻译计数由用例指定，用来验证 service 原样透传。
     *
     * <p>用 {@code doAnswer().when(mock)} 而不是 {@code when(mock).thenAnswer()}：后者在重新打桩时会
     * <b>真的调用一次</b>，触发上一次装的 answer（本例里就会拿 {@code any()} 占位符当参数而 NPE）。
     */
    private void stubTranslator(int retrySteps, int untranslated) {
        doAnswer(invocation -> {
            translatorCalls.incrementAndGet();
            List<TranscriptionSegment> in = invocation.getArgument(0);
            List<TranslatedSegment> out = in.stream()
                    .map(s -> new TranslatedSegment(s.beginTimeMs(), s.endTimeMs(), s.text(), "译文:" + s.text()))
                    .toList();
            return new SubtitleTranslator.TranslateResult(out, retrySteps, untranslated);
        }).when(subtitleTranslator).translate(any(), any(), any());
    }

    private SubtitleTranslationService service() {
        return new SubtitleTranslationService(workspaceService, asrProvider, JSON, PROPS,
                contextSummarizer, subtitleTranslator);
    }

    @BeforeEach
    void resetStubs() {
        translatorCalls.set(0);
        stubTranslator(0, 0);
        when(workspaceService.workspaceFor(any())).thenReturn(workDir);
        when(asrProvider.route()).thenReturn(SubtitleRoute.ASR_CLOUD);
        when(contextSummarizer.summarize(any())).thenReturn(Optional.of(SubtitleContext.empty()));
    }

    /**
     * 默认写一份「英文 -clean 字幕」：源语种 en ≠ 目标语种 zh，翻译器会真跑；
     * 需要跳过分支的用例再单独改摘要语种（摘要优先于 ASR 语种）。
     */
    private void writeCleanTrack() throws Exception {
        writeCleanTrack("en");
    }

    private void writeCleanTrack(String language) throws Exception {
        SubtitleTrack clean = new SubtitleTrack(SubtitleRoute.ASR_CLOUD, language, List.of(
                new TranscriptionSegment(0, 5000, "我防关这边露出"),
                new TranscriptionSegment(5000, 10000, "今天天气不错")));
        JSON.writeValue(cleanFile().toFile(), clean);
    }

    private Path cleanFile() {
        return workDir.resolve(SubtitleArtifactNames.cleanSegmentsFileName(SubtitleRoute.ASR_CLOUD));
    }

    private Path translateSegFile() {
        return workDir.resolve(SubtitleArtifactNames.translateSegmentsFileName(
                SubtitleRoute.ASR_CLOUD, PROPS.targetLanguage()));
    }

    private Path translateSrtFile() {
        return workDir.resolve(SubtitleArtifactNames.translateSrtFileName(
                SubtitleRoute.ASR_CLOUD, PROPS.targetLanguage()));
    }

    private Path contextFile() {
        return workDir.resolve(SubtitleArtifactNames.contextFileName(SubtitleRoute.ASR_CLOUD));
    }

    // ------------------------------------------------------------------
    // 正常路径与断点复用
    // ------------------------------------------------------------------

    @Test
    void translateWritesArtifactsAndDelegatesOnce() throws Exception {
        writeCleanTrack();
        SubtitleTranslationService s = service();

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"));

        // 两条 -translate 产物都在且非空
        assertTrue(Files.isRegularFile(translateSegFile()) && Files.size(translateSegFile()) > 0,
                "应产出 -translate.json");
        assertTrue(Files.isRegularFile(translateSrtFile()) && Files.size(translateSrtFile()) > 0,
                "应产出 -translate.srt");
        assertEquals(2, translated.segmentCount());
        assertFalse(translated.skipped());
        // 整份清单一次交给翻译器——内部分几批是它自己的事，service 不该管
        assertEquals(1, translatorCalls.get(), "整份清单应只交给翻译器一次");
    }

    @Test
    void translateReusesCheckpointSkipsTranslatorAndZeroesStats() throws Exception {
        writeCleanTrack();
        SubtitleTranslationService s = service();
        // 首跑带非零统计，好钉住「断点复用把它们一律归零」
        stubTranslator(2, 1);

        TranslatedSubtitle first = s.translate(new MediaInput.RemoteUrl("https://x"));
        assertEquals(2, first.retrySteps());
        assertEquals(1, first.untranslatedCount());

        // 二跑：-translate.json 已在，应断点复用，不再走翻译
        TranslatedSubtitle second = s.translate(new MediaInput.RemoteUrl("https://x"));

        assertEquals(1, translatorCalls.get(), "断点复用后不应再调翻译器");
        assertEquals(2, second.segmentCount());
        assertEquals(0, second.retrySteps(), "断点复用统计应归零");
        assertEquals(0, second.untranslatedCount(), "断点复用统计应归零（含未翻译段数）");
    }

    @Test
    void translateMissingCleanThrows() {
        SubtitleTranslationService s = service();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> s.translate(new MediaInput.RemoteUrl("https://x")));
        // 消息要说清「缺哪个文件、先跑哪一步」
        assertTrue(e.getMessage().contains(SubtitleArtifactNames.cleanSegmentsFileName(SubtitleRoute.ASR_CLOUD)),
                "消息应指明缺哪个文件");
        assertTrue(e.getMessage().contains("/api/subtitles/clean"), "消息应指明先跑哪一步");
    }

    @Test
    void translateNullInputThrows() {
        SubtitleTranslationService s = service();
        assertThrows(IllegalArgumentException.class, () -> s.translate(null));
    }

    // ------------------------------------------------------------------
    // 目标语种：卫语句须早于 I/O（error-handling.md 的硬规则）
    // ------------------------------------------------------------------

    @Test
    void resolveTargetLanguageNormalizes() {
        assertEquals("zh", SubtitleTranslationService.resolveTargetLanguage(props(" ZH ")));
    }

    @Test
    void resolveTargetLanguageBlankThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SubtitleTranslationService.resolveTargetLanguage(props("  ")));
        assertTrue(e.getMessage().contains("vidsage.subtitle.translate.target-language"),
                "消息应指明配置键");
    }

    @Test
    void resolveTargetLanguageNullThrows() {
        assertThrows(IllegalStateException.class,
                () -> SubtitleTranslationService.resolveTargetLanguage(props(null)));
    }

    /**
     * 这次修复真正要钉住的东西：配置里语种为空时，<b>一个字节的 I/O 都不该发生</b>——
     * 尤其不能先调 {@code workspaceFor} 建出工作目录再报错。断言位放在「所有服务调用之前」。
     */
    @Test
    void blankTargetLanguageThrowsBeforeAnyWorkspaceIo() {
        SubtitleTranslationService s = new SubtitleTranslationService(workspaceService, asrProvider, JSON,
                props("  "), contextSummarizer, subtitleTranslator);

        assertThrows(IllegalStateException.class, () -> s.translate(new MediaInput.RemoteUrl("https://x")));

        verify(workspaceService, never()).workspaceFor(any());
        verify(subtitleTranslator, never()).translate(any(), any(), any());
    }

    private static SubtitleTranslateProperties props(String targetLanguage) {
        return new SubtitleTranslateProperties(targetLanguage, 12, 400, 3, 20000);
    }

    // ------------------------------------------------------------------
    // 跳过分支（源语种 == 目标语种）
    // ------------------------------------------------------------------

    private SubtitleContext contextWithLanguage(String sourceLanguage) {
        return new SubtitleContext("主题", sourceLanguage, List.of());
    }

    private void assertSkipped(TranslatedSubtitle translated) throws Exception {
        assertTrue(translated.skipped(), "应标记为跳过");
        assertEquals(0, translatorCalls.get(), "跳过时不应调用翻译器");
        // 产物仍完整落盘，且译文照抄原文
        assertTrue(Files.isRegularFile(translateSegFile()) && Files.size(translateSegFile()) > 0);
        assertTrue(Files.isRegularFile(translateSrtFile()) && Files.size(translateSrtFile()) > 0);
        TranslatedTrack persisted = JSON.readValue(translateSegFile().toFile(), TranslatedTrack.class);
        assertTrue(persisted.skipped());
        for (TranslatedSegment seg : persisted.segments()) {
            assertEquals(seg.sourceText(), seg.translatedText(), "跳过时译文应等于原文");
        }
    }

    @Test
    void skipsWhenSourceLanguageEqualsTarget() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any())).thenReturn(Optional.of(contextWithLanguage("zh")));
        SubtitleTranslationService s = service();

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"));

        assertSkipped(translated);
        assertEquals(2, translated.segmentCount());
    }

    @Test
    void skipsWhenSourceLanguageHasRegionSubtag() throws Exception {
        writeCleanTrack();
        // LLM 可能回 zh-CN：直接 equals 会失配 → 中文被送去译成中文。只比主语言子标签才对
        when(contextSummarizer.summarize(any())).thenReturn(Optional.of(contextWithLanguage("zh-CN")));
        SubtitleTranslationService s = service();

        assertSkipped(s.translate(new MediaInput.RemoteUrl("https://x")));
    }

    @Test
    void doesNotSkipWhenLanguagesDiffer() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any())).thenReturn(Optional.of(contextWithLanguage("en")));
        SubtitleTranslationService s = service();

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"));

        assertFalse(translated.skipped());
        assertEquals(1, translatorCalls.get(), "语种不同应调用翻译器");
        assertEquals("en", translated.sourceLanguage());
    }

    @Test
    void doesNotSkipWhenSourceLanguageUnknown() throws Exception {
        // 摘要降级（空上下文）+ ASR language 为 null：源语种不可知，宁可多跑一趟也不静默漏译
        writeCleanTrack(null);
        when(contextSummarizer.summarize(any())).thenReturn(Optional.empty());
        SubtitleTranslationService s = service();

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"));

        assertFalse(translated.skipped(), "源语种为空时不应跳过");
        assertEquals("", translated.sourceLanguage());
        assertEquals(1, translatorCalls.get());
    }

    @Test
    void mainLanguageSubtagComparisonIgnoresRegionAndCase() {
        assertTrue(SubtitleTranslationService.isSameMainLanguage("zh-CN", "zh"));
        assertTrue(SubtitleTranslationService.isSameMainLanguage("zh-Hant", "ZH"));
        assertTrue(SubtitleTranslationService.isSameMainLanguage(" zh ", "zh"));
        assertTrue(SubtitleTranslationService.isSameMainLanguage("zh-tw", "zh-cn"));
        assertFalse(SubtitleTranslationService.isSameMainLanguage("zh", "en"));
        assertFalse(SubtitleTranslationService.isSameMainLanguage("", "zh"));
        assertFalse(SubtitleTranslationService.isSameMainLanguage("zh", "zhx"));
        // 两边都取不到语种：「未知 vs 未知」不是「同一门语言」——判成同一门会导致静默漏译
        assertFalse(SubtitleTranslationService.isSameMainLanguage("", ""));
        assertFalse(SubtitleTranslationService.isSameMainLanguage(null, null));
        assertFalse(SubtitleTranslationService.isSameMainLanguage("  ", null));
    }

    @Test
    void sourceLanguageResolvesFromContextFirstThenTrack() {
        assertEquals("de", SubtitleTranslationService.resolveSourceLanguage(
                contextWithLanguage("de"), "zh"), "摘要给的语种优先");
        assertEquals("zh", SubtitleTranslationService.resolveSourceLanguage(
                SubtitleContext.empty(), "zh"), "摘要为空时回退 ASR 语种");
        assertEquals("", SubtitleTranslationService.resolveSourceLanguage(
                SubtitleContext.empty(), null), "两者皆空时为空串");
    }

    // ------------------------------------------------------------------
    // 上下文缓存（design §3.2 / 决策 5）
    // ------------------------------------------------------------------

    @Test
    void summarizeSuccessWritesContextFile() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any()))
                .thenReturn(Optional.of(new SubtitleContext("中世纪剑术", "de", List.of())));
        SubtitleTranslationService s = service();

        s.translate(new MediaInput.RemoteUrl("https://x"));

        assertTrue(Files.isRegularFile(contextFile()) && Files.size(contextFile()) > 0,
                "摘要成功应把上下文落盘");
        SubtitleContext persisted = JSON.readValue(contextFile().toFile(), SubtitleContext.class);
        assertEquals("de", persisted.sourceLanguage(), "落盘的该是成功那份上下文");
        verify(contextSummarizer).summarize(any());
    }

    @Test
    void contextCacheHitSkipsSummarize() throws Exception {
        writeCleanTrack();
        JSON.writeValue(contextFile().toFile(), new SubtitleContext("中世纪剑术", "de", List.of()));
        SubtitleTranslationService s = service();

        s.translate(new MediaInput.RemoteUrl("https://x"));

        verify(contextSummarizer, never()).summarize(any());
    }

    @Test
    void summarizeDegradeDoesNotWriteContextFile() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any())).thenReturn(Optional.empty());
        SubtitleTranslationService s = service();

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"));

        assertFalse(Files.exists(contextFile()),
                "降级成空上下文绝不能落盘，否则下次会命中这份空缓存");
        assertEquals(2, translated.segmentCount(), "摘要失败不该拦住翻译");
        assertEquals(1, translatorCalls.get(), "翻译照常进行");
    }
}
