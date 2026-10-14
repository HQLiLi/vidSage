package com.vidsage.application.subtitle.pipeline;

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

import com.vidsage.application.media.WorkspaceKeyGenerator;
import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.subtitle.model.term.TermLookupCommand;
import com.vidsage.domain.subtitle.model.term.TermLookupResult;
import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;
import com.vidsage.domain.subtitle.model.translation.TranslatedSegment;
import com.vidsage.domain.subtitle.model.translation.TranslatedSubtitle;
import com.vidsage.domain.subtitle.model.translation.TranslatedTrack;
import com.vidsage.domain.subtitle.model.term.VerifiedTerm;
import com.vidsage.domain.subtitle.model.term.VerifiedTerms;
import com.vidsage.domain.subtitle.port.SubtitleContextSummarizer;
import com.vidsage.domain.subtitle.port.SubtitleTranslator;
import com.vidsage.domain.subtitle.model.translation.TranslateResult;
import com.vidsage.domain.subtitle.port.TermLookupProvider;
import com.vidsage.domain.subtitle.model.common.SubtitleArtifactSpec;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
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
 * SubtitleTranslationCoordinator 的纯单元测试：<b>不起 Spring 容器</b>。
 *
 * <p>只测<b>编排</b>：定位产物 → 断点复用 → 上下文缓存 → 源语种跳过判定 → 术语查证（触发条件、
 * 失败分流、产物落盘）→ 交给 {@link SubtitleTranslator} → 写 -translate 产物。摘要器、翻译器与
 * 查证 provider 都是 mock/桩。
 *
 * <p><b>翻译算法本身（分批、校验、重试、回退原文）不在这里测</b>。
 */
class SubtitleTranslationCoordinatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 目标语种：既有用例的产物名与断言都按它写（原来读的是配置里的 "zh"）。 */
    private static final SupportedLanguage TARGET = SupportedLanguage.SIMPLE_CHINESE;

    /** 源语种：默认与目标不同（真翻）。多语种后源语种是调用方的断言，不再从摘要上下文里推断。 */
    private static final SupportedLanguage SOURCE = SupportedLanguage.JAPANESE;

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private final WorkspaceKeyGenerator workspaceKeyGenerator = mock(WorkspaceKeyGenerator.class);
    private final WorkspaceDirectoryManager workspaceDirectoryManager = mock(WorkspaceDirectoryManager.class);
    private final SubtitleContextSummarizer contextSummarizer = mock(SubtitleContextSummarizer.class);
    private final SubtitleTranslator subtitleTranslator = mock(SubtitleTranslator.class);

    /** 翻译器被调了几次——「跳过 / 断点复用后不再跑」这类断言靠它。 */
    private final AtomicInteger translatorCalls = new AtomicInteger();

    /** 每次真翻译时翻译器收到的官方译名表（按调用顺序）——「terms 产物有没有传下去」这类断言靠它。 */
    private final List<List<VerifiedTerm>> officialTermsReceived = new ArrayList<>();

    /**
     * 翻译器的桩：给每条加「译文:」前缀，便于区分「真翻译」与「跳过时照抄原文」；调用次数记进计数器，
     * 第 4 个入参（官方译名表）记进 {@link #officialTermsReceived}。返回值里的重试 / 未翻译计数由用例
     * 指定，用来验证 service 原样透传。
     *
     * <p>用 {@code doAnswer().when(mock)} 而不是 {@code when(mock).thenAnswer()}：后者在重新打桩时会
     * <b>真的调用一次</b>，触发上一次装的 answer（本例里就会拿 {@code any()} 占位符当参数而 NPE）。
     */
    private void stubTranslator(int retrySteps, int untranslated) {
        doAnswer(invocation -> {
            translatorCalls.incrementAndGet();
            officialTermsReceived.add(invocation.getArgument(3));
            List<TranscriptionSegment> in = invocation.getArgument(0);
            List<TranslatedSegment> out = in.stream()
                    .map(s -> new TranslatedSegment(s.beginTimeMs(), s.endTimeMs(), s.text(), "译文:" + s.text()))
                    .toList();
            return new TranslateResult(out, retrySteps, untranslated);
        }).when(subtitleTranslator).translate(any(), any(), any(), any(), any());
    }

    private SubtitleTranslationCoordinator service() {
        // 查证关闭态（provider 缺席）：查证静默跳过，本文件既有用例只测翻译编排本身
        return service(absent());
    }

    private SubtitleTranslationCoordinator service(ObjectProvider<TermLookupProvider> lookupProvider) {
        return new SubtitleTranslationCoordinator(workspaceKeyGenerator, workspaceDirectoryManager, JSON,
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
        officialTermsReceived.clear();
        stubTranslator(0, 0);
        when(workspaceKeyGenerator.parseMediaToWorkspaceKey(any())).thenReturn(new WorkspaceKey("a1b2c3d4e5f6"));
        when(workspaceDirectoryManager.ensureWorkspaceDirectory(any(), any())).thenReturn(workDir);
        when(contextSummarizer.summarize(any(), any())).thenReturn(Optional.of(SubtitleContext.empty()));
    }

    /**
     * 默认写一份「英文 -clean 字幕」：源语种 en ≠ 目标语种 zh，翻译器会真跑；
     * 需要跳过分支的用例再单独改摘要语种（摘要优先于 ASR 语种）。
     */
    private void writeCleanTrack() throws Exception {
        writeCleanTrack("en");
    }

    private void writeCleanTrack(String language) throws Exception {
        SubtitleTrack clean = new SubtitleTrack(language, List.of(
                new TranscriptionSegment(0, 5000, "我防关这边露出"),
                new TranscriptionSegment(5000, 10000, "今天天气不错")), null);
        JSON.writeValue(cleanFile().toFile(), clean);
    }

    private Path cleanFile() {
        return workDir.resolve(SubtitleArtifactSpec.CLEAN_SEGMENTS.getFileName(null));
    }

    private Path translateSegFile() {
        return workDir.resolve(SubtitleArtifactSpec.TRANSLATED_SEGMENTS.getFileName(TARGET.getCode()));
    }

    private Path translateSrtFile() {
        return workDir.resolve(SubtitleArtifactSpec.TRANSLATED_SRT.getFileName(TARGET.getCode()));
    }

    private Path contextFile() {
        return workDir.resolve(SubtitleArtifactSpec.CONTEXT.getFileName(null));
    }

    private Path termsFile() {
        return workDir.resolve(SubtitleArtifactSpec.TERMS.getFileName(TARGET.getCode()));
    }

    // ------------------------------------------------------------------
    // 正常路径与断点复用
    // ------------------------------------------------------------------

    @Test
    void translateWritesArtifactsAndDelegatesOnce() throws Exception {
        writeCleanTrack();
        SubtitleTranslationCoordinator s = service();

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET);

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
        SubtitleTranslationCoordinator s = service();
        // 首跑带非零统计，好钉住「断点复用把它们一律归零」
        stubTranslator(2, 1);

        TranslatedSubtitle first = s.translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET);
        assertEquals(2, first.retrySteps());
        assertEquals(1, first.untranslatedCount());

        // 二跑：-translate.json 已在，应断点复用，不再走翻译
        TranslatedSubtitle second = s.translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET);

        assertEquals(1, translatorCalls.get(), "断点复用后不应再调翻译器");
        assertEquals(2, second.segmentCount());
        assertEquals(0, second.retrySteps(), "断点复用统计应归零");
        assertEquals(0, second.untranslatedCount(), "断点复用统计应归零（含未翻译段数）");
    }

    @Test
    void translateMissingCleanThrows() {
        SubtitleTranslationCoordinator s = service();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> s.translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET));
        // 消息要说清「缺哪个文件、先跑哪一步」
        assertTrue(e.getMessage().contains(SubtitleArtifactSpec.CLEAN_SEGMENTS.getFileName(null)),
                "消息应指明缺哪个文件");
        assertTrue(e.getMessage().contains("/api/subtitles/clean"), "消息应指明先跑哪一步");
    }

    @Test
    void translateNullInputThrows() {
        SubtitleTranslationCoordinator s = service();
        assertThrows(IllegalArgumentException.class, () -> s.translate(null, SOURCE, TARGET));
    }

    // ------------------------------------------------------------------
    // 翻译检查点判定（管线触发翻译前的幂等判据）
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 目标语种：卫语句须早于 I/O（error-handling.md 的硬规则）
    // （语种归一化与守卫本身的断言在 domain/subtitle/policy/Bcp47LanguagePolicyTest）
    // ------------------------------------------------------------------

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
        SubtitleTranslationCoordinator s = service();

        // 源语种与目标语种解析成同一个枚举（zh / zh-CN / zh-SG / zh-Hans 都归一到这里）→ 跳过，不调 LLM
        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"), TARGET, TARGET);

        assertSkipped(translated);
        assertEquals(2, translated.segmentCount());
    }

    @Test
    void doesNotSkipWhenLanguagesDiffer() throws Exception {
        writeCleanTrack();
        SubtitleTranslationCoordinator s = service();

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET);

        assertFalse(translated.skipped());
        assertEquals(1, translatorCalls.get(), "语种不同应调用翻译器");
        assertEquals(SOURCE.getCode(), translated.sourceLanguage(), "源语种按调用方给的原样带进产物");
    }

    // ------------------------------------------------------------------
    // 进度事件（管线通过 listener 拿阶段边界与批级进度）
    // ------------------------------------------------------------------

    @Test
    void translateWithListenerEmitsBeginBatchAndEndEvents() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any(), any())).thenReturn(Optional.of(contextWithLanguage("en")));
        // 真实批级事件来自 SubtitleTranslator；本层只验「批级事件原样透传 + 首尾事件齐全」
        doAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(4);
            listener.onProgress(new StageProgress(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), 100, "第 1/1 批",
                    StageProgress.State.RUNNING));
            List<TranscriptionSegment> in = invocation.getArgument(0);
            List<TranslatedSegment> out = in.stream()
                    .map(s -> new TranslatedSegment(s.beginTimeMs(), s.endTimeMs(), s.text(), "译文:" + s.text()))
                    .toList();
            return new TranslateResult(out, 0, 0);
        }).when(subtitleTranslator).translate(any(), any(), any(), any(), any());
        List<StageProgress> events = new ArrayList<>();

        service().translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET, events::add);

        assertEquals(3, events.size(), () -> "应发「开始 → 批 → 完成」三条：" + events);
        assertEquals(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), events.get(0).stage());
        assertEquals(0, events.get(0).progress());
        assertEquals(StageProgress.State.RUNNING, events.get(0).state());
        assertEquals("第 1/1 批", events.get(1).message(), "批级事件应原样透传");
        assertEquals(100, events.get(2).progress());
        assertEquals(StageProgress.State.SUCCEEDED, events.get(2).state());
    }

    @Test
    void translateCheckpointHitEmitsSingleSkippedEvent() throws Exception {
        writeCleanTrack();
        SubtitleTranslationCoordinator s = service();
        s.translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET);

        List<StageProgress> events = new ArrayList<>();
        s.translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET, events::add);

        assertEquals(1, events.size(), () -> "断点复用只发一条 SKIPPED：" + events);
        assertEquals(StageProgress.State.SKIPPED, events.get(0).state());
        assertEquals(100, events.get(0).progress());
        assertEquals(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), events.get(0).stage());
    }

    @Test
    void sameLanguageSkipEmitsBeginThenSkippedEvent() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any(), any())).thenReturn(Optional.of(contextWithLanguage("zh")));
        List<StageProgress> events = new ArrayList<>();

        service().translate(new MediaInput.RemoteUrl("https://x"), TARGET, TARGET, events::add);

        // 阶段先「开始」（做完了读 -clean / 上下文这些前置工作），LLM 翻译跳过后以 SKIPPED 收尾
        assertEquals(2, events.size(), () -> "同语种跳过应发「开始 → SKIPPED」两条：" + events);
        assertEquals(StageProgress.State.RUNNING, events.get(0).state());
        assertEquals(0, events.get(0).progress());
        assertEquals(StageProgress.State.SKIPPED, events.get(1).state());
        assertEquals(100, events.get(1).progress());
    }

    @Test
    void translateRejectsNullListener() throws Exception {
        writeCleanTrack();
        assertThrows(IllegalArgumentException.class,
                () -> service().translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET, null));
    }

    // ------------------------------------------------------------------
    // 上下文缓存（design §3.2 / 决策 5）
    // ------------------------------------------------------------------

    @Test
    void summarizeSuccessDoesNotWriteContextFile() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any(), any()))
                .thenReturn(Optional.of(new SubtitleContext("中世纪剑术", "de", List.of())));
        SubtitleTranslationCoordinator s = service();

        s.translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET);

        // 翻译侧的摘要不带关键词（关键词是清洗那次的入参，翻译拿不到），落盘会盖掉清洗侧那份带关键词的；
        // 而清洗命中断点复用后不会再来覆盖，这份残次品就常驻了——所以只喂本次翻译，不落盘
        assertFalse(Files.exists(contextFile()), "翻译侧摘要不落盘，不污染清洗侧的上下文缓存");
        verify(contextSummarizer).summarize(any(), any());
    }

    @Test
    void contextCacheHitSkipsSummarize() throws Exception {
        writeCleanTrack();
        JSON.writeValue(contextFile().toFile(), new SubtitleContext("中世纪剑术", "de", List.of()));
        SubtitleTranslationCoordinator s = service();

        s.translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET);

        verify(contextSummarizer, never()).summarize(any(), any());
    }

    @Test
    void summarizeDegradeDoesNotWriteContextFile() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any(), any())).thenReturn(Optional.empty());
        SubtitleTranslationCoordinator s = service();

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET);

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
        when(contextSummarizer.summarize(any(), any()))
                .thenReturn(Optional.of(contextWithTerms("en")));
        // 只狼=官方、弹刀=弃权（查过无据）、架势条=没配结论（调用失败）——三种结论一次钉住
        SubtitleTranslationCoordinator s = service(present(stubLookup(
                "只狼", "official:Sekiro",
                "弹刀", "")));

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET);

        assertFalse(translated.skipped(), "查证不得改变翻译主流程");
        assertTrue(Files.isRegularFile(termsFile()) && Files.size(termsFile()) > 0,
                "真翻译应生成 terms 产物");
        // 触发是确定性的：全量逐条查证，不做「哪些值得查」的预过滤
        assertEquals(3, lookupCommands.size());
        assertEquals("只狼", lookupCommands.get(0).term(), "查证命令按 fixed 写法发起");
        assertEquals(TARGET.getCode(), lookupCommands.get(0).targetLanguage());
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
        when(contextSummarizer.summarize(any(), any()))
                .thenReturn(Optional.of(contextWithTerms("en")));
        JSON.writeValue(termsFile().toFile(), new VerifiedTerms(List.of(
                new VerifiedTerm("只狼", "只狼", "游戏名", "Sekiro", "官方译名（来源：example.com）"))));

        service(present(stubLookup("只狼", "official:Sekiro")))
                .translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET);

        assertEquals(0, lookupCommands.size(),
                "terms 产物已存在 = 已查证，零查证调用（删文件即重查）");
    }

    @Test
    void sameLanguageDoesNotLookUp() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any(), any()))
                .thenReturn(Optional.of(contextWithTerms("zh")));
        SubtitleTranslationCoordinator s = service(present(stubLookup("只狼", "official:Sekiro")));

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"), TARGET, TARGET);

        assertTrue(translated.skipped(), "同语种应跳过翻译");
        assertEquals(0, lookupCommands.size(), "同语种不翻译，也就不查证——目标语种默认 zh 的中文素材不烧搜索费");
        assertFalse(Files.exists(termsFile()), "同语种不应产出 terms 文件");
    }

    @Test
    void providerAbsentSkipsLookupAndWritesNoTermsFile() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any(), any()))
                .thenReturn(Optional.of(contextWithTerms("en")));
        SubtitleTranslationCoordinator s = service(absent());

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET);

        assertFalse(translated.skipped(), "查证关闭不得挡翻译");
        assertEquals(1, translatorCalls.get(), "翻译照常进行");
        assertFalse(Files.exists(termsFile()), "provider 缺席不应产出 terms 文件");
    }

    @Test
    void allLookupsFailWritesNoTermsFile() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any(), any()))
                .thenReturn(Optional.of(contextWithTerms("en")));
        // 桩没给任何术语配结论 → 全部抛异常 = 全部查证失败
        SubtitleTranslationCoordinator s = service(present(stubLookup()));

        TranslatedSubtitle translated = s.translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET);

        assertFalse(Files.exists(termsFile()),
                "全部失败不落产物——空产物会把整批词固化成「已查证」，下次永远不会再补查");
        assertFalse(translated.skipped(), "查证全失败不得挡翻译");
        assertEquals(1, translatorCalls.get(), "翻译照常进行");
    }

    // ------------------------------------------------------------------
    // 官方译名接入翻译（terms-<route>-<target>.json 的第一个消费方）
    // ------------------------------------------------------------------

    @Test
    void realTranslatePassesVerifiedTermsToTranslator() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any(), any()))
                .thenReturn(Optional.of(contextWithTerms("en")));
        JSON.writeValue(termsFile().toFile(), new VerifiedTerms(List.of(
                new VerifiedTerm("只狼", "只狼", "游戏名", "Sekiro", "官方译名（来源：example.com）"),
                new VerifiedTerm("弹刀", "弹刀", "机制名", "", "弃权"))));

        service().translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET);

        assertEquals(1, translatorCalls.get());
        List<VerifiedTerm> received = officialTermsReceived.get(0);
        assertEquals(2, received.size(), "产物里的官方译名与弃权结论都要传给翻译器（弃权由翻译器分流渲染）");
        assertEquals("Sekiro", received.get(0).official());
        assertEquals("", received.get(1).official());
    }

    @Test
    void missingTermsFilePassesEmptyOfficialTerms() throws Exception {
        writeCleanTrack();
        when(contextSummarizer.summarize(any(), any()))
                .thenReturn(Optional.of(contextWithTerms("en")));
        // provider 缺席 = 查证关闭 = terms 文件不存在：翻译照常，官方译名按空表
        service().translate(new MediaInput.RemoteUrl("https://x"), SOURCE, TARGET);

        assertEquals(1, translatorCalls.get(), "官方译名是增益不是前提，缺产物不挡翻译");
        assertTrue(officialTermsReceived.get(0).isEmpty(), "无产物应传空表而不是 null");
    }
}
