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
import com.vidsage.model.subtitle.TermLookupCommand;
import com.vidsage.model.subtitle.TermLookupResult;
import com.vidsage.model.subtitle.TranslatedSegment;
import com.vidsage.model.subtitle.TranslatedSubtitle;
import com.vidsage.model.subtitle.TranslatedTrack;
import com.vidsage.model.subtitle.VerifiedTerm;
import com.vidsage.model.subtitle.VerifiedTerms;
import com.vidsage.provider.asr.ASRProvider;
import com.vidsage.provider.llm.SubtitleContextSummarizer;
import com.vidsage.provider.llm.SubtitleTranslator;
import com.vidsage.provider.llm.termlookup.TermLookupProvider;
import com.vidsage.utils.subtitle.SubtitleArtifactNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * SubtitleTranslationService 的纯单元测试：<b>不起 Spring 容器</b>。
 *
 * <p>只测<b>编排</b>：定位产物 → 断点复用 → 上下文缓存 → 源语种跳过判定 → 术语查证（触发条件、
 * 失败分流、产物落盘）→ 交给 {@link SubtitleTranslator} → 写 -translate 产物。摘要器、翻译器与
 * 查证 provider 都是 mock/桩。
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
        // 查证关闭态（provider 缺席）：查证静默跳过，本文件既有用例只测翻译编排本身
        return service(absent());
    }

    private SubtitleTranslationService service(ObjectProvider<TermLookupProvider> lookupProvider) {
        return new SubtitleTranslationService(workspaceService, asrProvider, JSON, PROPS,
                contextSummarizer, lookupProvider, subtitleTranslator);
    }

    /** 查证 provider 缺席的 ObjectProvider：getIfAvailable() 返回 null，等价于 yml 删掉 provider 行。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<TermLookupProvider> absent() {
        return mock(ObjectProvider.class);
    }

    /** 查证 provider 收到的命令——「全量逐条 / 零调用」这类断言靠它。 */
    private final List<TermLookupCommand> lookupCommands = new ArrayList<>();

    /**
     * 查证 provider 的桩：按术语名返回预设结论，behavior 按 [术语名, 结论] 成对给出；
     * 没给到的术语一律抛异常（模拟查证调用失败）。结论写法：
     * {@code "official:Sekiro"} = 官方译名、{@code "posture meter"} = 通用译名、
     * {@code ""} = 弃权（none）、{@code "throw:..."} 前缀 = 抛异常。
     */
    private TermLookupProvider stubLookup(String... behavior) {
        return command -> {
            lookupCommands.add(command);
            for (int i = 0; i + 1 < behavior.length; i += 2) {
                if (behavior[i].equals(command.term())) {
                    String verdict = behavior[i + 1];
                    if (verdict.startsWith("throw")) {
                        throw new IllegalStateException("模拟查证失败");
                    }
                    if (verdict.isEmpty()) {
                        return TermLookupResult.none();
                    }
                    boolean official = verdict.startsWith("official:");
                    String name = official ? verdict.substring("official:".length()) : verdict;
                    return new TermLookupResult(name, official, "https://example.com/wiki", "引文 " + name);
                }
            }
            throw new IllegalStateException("模拟查证失败");
        };
    }

    private static ObjectProvider<TermLookupProvider> present(TermLookupProvider p) {
        return new ObjectProvider<>() {
            @Override
            public TermLookupProvider getObject() {
                return p;
            }

            @Override
            public TermLookupProvider getIfAvailable() {
                return p;
            }
        };
    }

    @BeforeEach
    void resetStubs() {
        translatorCalls.set(0);
        lookupCommands.clear();
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

    private Path termsFile() {
        return workDir.resolve(SubtitleArtifactNames.verifiedTermsFileName(
                SubtitleRoute.ASR_CLOUD, PROPS.targetLanguage()));
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
                props("  "), contextSummarizer, absent(), subtitleTranslator);

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

    // ------------------------------------------------------------------
    // 术语查证编排（内联在翻译流程，design §三；产物 terms-<route>-<target>.json）
    // ------------------------------------------------------------------

    /** 一份带三条术语的上下文：官方可查 / 弃权 / 失败各占一个名额。 */
    private SubtitleContext contextWithTerms(String sourceLanguage) {
        return new SubtitleContext("游戏动作解析", sourceLanguage, List.of(
                new SubtitleContext.Term("只狼", "只狼", "游戏名"),
                new SubtitleContext.Term("弹刀", "弹刀", "机制名"),
                new SubtitleContext.Term("架势条", "架势条", "UI 元素")));
    }

    @Test
    void realTranslateLooksUpAllTermsAndWritesTermsFile() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any()))
                .thenReturn(Optional.of(contextWithTerms("en")));
        // 只狼=官方、弹刀=弃权（查过无据）、架势条=没配结论（调用失败）——三种结论一次钉住
        SubtitleTranslationService s = service(present(stubLookup(
                "只狼", "official:Sekiro",
                "弹刀", "")));

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"));

        assertFalse(translated.skipped(), "查证不得改变翻译主流程");
        assertTrue(Files.isRegularFile(termsFile()) && Files.size(termsFile()) > 0,
                "真翻译应生成 terms 产物");
        // 触发是确定性的：全量逐条查证，不做「哪些值得查」的预过滤
        assertEquals(3, lookupCommands.size());
        assertEquals("只狼", lookupCommands.get(0).term(), "查证命令按 fixed 写法发起");
        assertEquals("zh", lookupCommands.get(0).targetLanguage());
        assertEquals("游戏动作解析", lookupCommands.get(0).theme());
        // 产物内容：官方带等级与来源 host、弃权空串持久化、失败词不进文件
        VerifiedTerms persisted = JSON.readValue(termsFile().toFile(), VerifiedTerms.class);
        assertEquals(2, persisted.terms().size(), "调用失败的词不进产物文件");
        VerifiedTerm officialHit = persisted.terms().get(0);
        assertEquals("Sekiro", officialHit.official());
        assertTrue(officialHit.officialNote().contains("官方译名"), "note 记译名等级");
        assertTrue(officialHit.officialNote().contains("example.com"), "note 记来源 host，不记完整 URL");
        VerifiedTerm abstained = persisted.terms().get(1);
        assertEquals("", abstained.official(), "弃权 = 空串落盘，不再重搜");
        assertTrue(abstained.officialNote().contains("弃权"));
    }

    @Test
    void existingTermsFileSkipsLookup() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any()))
                .thenReturn(Optional.of(contextWithTerms("en")));
        JSON.writeValue(termsFile().toFile(), new VerifiedTerms(List.of(
                new VerifiedTerm("只狼", "只狼", "游戏名", "Sekiro", "官方译名（来源：example.com）"))));

        service(present(stubLookup("只狼", "official:Sekiro")))
                .translate(new MediaInput.RemoteUrl("https://x"));

        assertEquals(0, lookupCommands.size(),
                "terms 产物已存在 = 已查证，零查证调用（删文件即重查）");
    }

    @Test
    void sameLanguageDoesNotLookUp() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any()))
                .thenReturn(Optional.of(contextWithTerms("zh")));
        SubtitleTranslationService s = service(present(stubLookup("只狼", "official:Sekiro")));

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"));

        assertTrue(translated.skipped(), "同语种应跳过翻译");
        assertEquals(0, lookupCommands.size(), "同语种不翻译，也就不查证——目标语种默认 zh 的中文素材不烧搜索费");
        assertFalse(Files.exists(termsFile()), "同语种不应产出 terms 文件");
    }

    @Test
    void sourceLanguageUnknownDoesNotLookUp() throws Exception {
        // 摘要降级（空上下文、无术语）+ ASR language 为 null：源语种不可知。翻译照常（宁可多跑一趟），
        // 但无术语可查——零查证调用、不落 terms 文件
        writeCleanTrack(null);
        when(contextSummarizer.summarize(any())).thenReturn(Optional.empty());
        SubtitleTranslationService s = service(present(stubLookup("只狼", "official:Sekiro")));

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"));

        assertFalse(translated.skipped(), "源语种为空时翻译照常");
        assertEquals(0, lookupCommands.size(), "无术语可查，不应发起查证调用");
        assertFalse(Files.exists(termsFile()));
    }

    @Test
    void providerAbsentSkipsLookupAndWritesNoTermsFile() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any()))
                .thenReturn(Optional.of(contextWithTerms("en")));
        SubtitleTranslationService s = service(absent());

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"));

        assertFalse(translated.skipped(), "查证关闭不得挡翻译");
        assertEquals(1, translatorCalls.get(), "翻译照常进行");
        assertFalse(Files.exists(termsFile()), "provider 缺席不应产出 terms 文件");
    }

    @Test
    void allLookupsFailWritesNoTermsFile() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any()))
                .thenReturn(Optional.of(contextWithTerms("en")));
        // 桩没给任何术语配结论 → 全部抛异常 = 全部查证失败
        SubtitleTranslationService s = service(present(stubLookup()));

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"));

        assertFalse(Files.exists(termsFile()),
                "全部失败不落产物——空产物会把整批词固化成「已查证」，下次永远不会再补查");
        assertFalse(translated.skipped(), "查证全失败不得挡翻译");
        assertEquals(1, translatorCalls.get(), "翻译照常进行");
    }
}
