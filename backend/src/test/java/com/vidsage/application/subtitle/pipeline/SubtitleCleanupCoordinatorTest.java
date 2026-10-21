package com.vidsage.application.subtitle.pipeline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

import com.vidsage.config.SubtitleCleanupProperties;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.model.cleanup.CleanedSubtitle;
import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.subtitle.model.cleanup.CleanseResult;
import com.vidsage.domain.subtitle.port.SubtitleCleanser;
import com.vidsage.domain.subtitle.port.SubtitleContextSummarizer;
import com.vidsage.domain.subtitle.model.common.SubtitleArtifactSpec;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * SubtitleCleanupCoordinator 的纯单元测试：<b>不起 Spring 容器</b>。
 *
 * <p>只测<b>编排</b>：定位产物 → 断点复用 → 切分 → 交给 {@link SubtitleCleanser} → 写 -clean 产物。
 * 摘要器与 cleanser 都是 mock；清洗算法本身（分批、守卫、重试、保留原文）不在这里测。
 */
class SubtitleCleanupCoordinatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SubtitleCleanupProperties PROPS =
            new SubtitleCleanupProperties(8000, 600, 20, 3, 0.7, 0.3, 20000, false);

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private final SubtitleContextSummarizer contextSummarizer = mock(SubtitleContextSummarizer.class);
    private final SubtitleCleanser subtitleCleanser = mock(SubtitleCleanser.class);

    /** cleanser 被调了几次——「断点复用后不再跑」这类断言靠它。 */
    private final AtomicInteger cleanserCalls = new AtomicInteger();

    /** cleanser 的桩：原样返回入参（零修正、零重试），只记调用次数。 */
    private void stubCleanserEcho() {
        doAnswer(invocation -> {
            cleanserCalls.incrementAndGet();
            List<TranscriptionSegment> in = invocation.getArgument(0);
            return new CleanseResult(in, 0, 0, 0, 0);
        }).when(subtitleCleanser).clean(any(), any(), any(), any());
    }

    private SubtitleCleanupCoordinator service() {
        return new SubtitleCleanupCoordinator(JSON, PROPS, contextSummarizer, subtitleCleanser);
    }

    private void writeRawTrack() throws Exception {
        SubtitleTrack raw = new SubtitleTrack("zh", List.of(
                new TranscriptionSegment(0, 5000, "我防关这边露出"),
                new TranscriptionSegment(5000, 10000, "今天天气不错")), null);
        JSON.writeValue(workDir.resolve(SubtitleArtifactSpec.ORIGINAL_SEGMENTS.getFileName(null)).toFile(), raw);
    }

    /**
     * 预置一份带指定关键词指纹的 {@code -clean} 检查点——断点复用与摘要缓存判据都以它为前提。
     *
     * @param fingerprint 写进产物的那枚指纹；传 {@code null} 模拟存量产物（没有这个字段）
     */
    private void writeCleanTrack(String fingerprint) throws Exception {
        SubtitleTrack clean = new SubtitleTrack("zh", List.of(
                new TranscriptionSegment(0, 5000, "我防关这边露出"),
                new TranscriptionSegment(5000, 10000, "今天天气不错")), fingerprint);
        JSON.writeValue(workDir.resolve(SubtitleArtifactSpec.CLEAN_SEGMENTS.getFileName(null)).toFile(), clean);
    }

    @BeforeEach
    void resetStubs() {
        cleanserCalls.set(0);
        stubCleanserEcho();
        when(contextSummarizer.summarize(any(), any())).thenReturn(Optional.of(SubtitleContext.empty()));
    }

    @Test
    void cleanWritesCleanArtifactsAndDelegatesOnce() throws Exception {
        writeRawTrack();
        SubtitleCleanupCoordinator s = service();

        CleanedSubtitle cleaned = s.clean(new MediaInput.RemoteUrl("https://x"), workDir, new SubtitleKeywords(List.of()), true);

        // 两条 -clean 产物都在且非空
        Path cleanSeg = workDir.resolve(SubtitleArtifactSpec.CLEAN_SEGMENTS.getFileName(null));
        Path cleanSrt = workDir.resolve(SubtitleArtifactSpec.CLEAN_SRT.getFileName(null));
        assertTrue(Files.isRegularFile(cleanSeg) && Files.size(cleanSeg) > 0, "应产出 -clean.json");
        assertTrue(Files.isRegularFile(cleanSrt) && Files.size(cleanSrt) > 0, "应产出 -clean.srt");
        assertEquals(2, cleaned.segmentCount());
        // 整份清单一次交给 cleanser——内部分几批是它自己的事，service 不该管
        assertEquals(1, cleanserCalls.get(), "整份清单应只交给 cleanser 一次");
    }

    @Test
    void cleanReusesCheckpointSkipsCleanser() throws Exception {
        writeRawTrack();
        SubtitleKeywords keywords = new SubtitleKeywords(List.of());
        SubtitleCleanupCoordinator s = service();
        s.clean(new MediaInput.RemoteUrl("https://x"), workDir, keywords, true);
        assertEquals(1, cleanserCalls.get());

        // 二跑：-clean.json 已在且指纹一致 → reuseIfAvailable 命中，不再走切分与清洗
        Optional<CleanedSubtitle> reused = s.reuseIfAvailable(workDir, keywords, true, ProgressListener.noop());

        assertTrue(reused.isPresent(), "检查点在且指纹一致就该命中");
        assertEquals(2, reused.get().segmentCount());
        assertEquals(1, cleanserCalls.get(), "复用命中后不应再调 cleanser");
    }

    @Test
    void cleanRerunsWhenArtifactGateSaysNotReusable() throws Exception {
        writeRawTrack();
        SubtitleKeywords keywords = new SubtitleKeywords(List.of());
        SubtitleCleanupCoordinator s = service();
        s.clean(new MediaInput.RemoteUrl("https://x"), workDir, keywords, true);
        assertEquals(1, cleanserCalls.get());

        // 盘上 -clean 完好、关键词也没变，但产物表说这一版已作废 → 不能拿盘上旧检查点当命中
        assertTrue(s.reuseIfAvailable(workDir, keywords, false, ProgressListener.noop()).isEmpty(),
                "门禁关掉时不许拿盘上旧检查点当命中");

        s.clean(new MediaInput.RemoteUrl("https://x"), workDir, keywords, false);
        assertEquals(2, cleanserCalls.get(), "门禁关掉时必须重跑清洗");
    }

    @Test
    void emptyTranscriptionWritesNonEmptyCheckpointDespiteZeroByteSrt() throws Exception {
        // 空转写是合法产出（纯音乐 / 无语音）：raw segments=[]
        SubtitleTrack empty = new SubtitleTrack("zh", List.of(), null);
        JSON.writeValue(workDir.resolve(SubtitleArtifactSpec.ORIGINAL_SEGMENTS.getFileName(null)).toFile(), empty);
        SubtitleKeywords keywords = new SubtitleKeywords(List.of());
        SubtitleCleanupCoordinator s = service();

        CleanedSubtitle first = s.clean(new MediaInput.RemoteUrl("https://x"), workDir, keywords, true);

        Path cleanSeg = workDir.resolve(SubtitleArtifactSpec.CLEAN_SEGMENTS.getFileName(null));
        Path cleanSrt = workDir.resolve(SubtitleArtifactSpec.CLEAN_SRT.getFileName(null));
        assertEquals(0, first.segmentCount());
        assertEquals(0, first.srtSizeBytes());
        // 关键：SRT 是 0 字节，但检查点 JSON 必须非空——否则下次判「未完成」会反复重跑（死循环）
        assertTrue(Files.size(cleanSeg) > 0, "空转写的 -clean.json 也必须非空，才能当检查点");
        assertEquals(0L, Files.size(cleanSrt), "空转写产出 0 字节 SRT");

        // 二跑：断点命中直接返回，不重跑（空转写不死循环）
        Optional<CleanedSubtitle> reused = s.reuseIfAvailable(workDir, keywords, true, ProgressListener.noop());
        assertTrue(reused.isPresent(), "0 字节 SRT 但检查点 JSON 非空，仍能命中");
        assertEquals(0, reused.get().segmentCount());
        assertEquals(1, cleanserCalls.get(), "空转写二跑应走断点复用，不再调 cleanser");
    }

    @Test
    void cleanMissingRawThrows() {
        SubtitleCleanupCoordinator s = service();
        assertThrows(IllegalStateException.class, () -> s.clean(new MediaInput.RemoteUrl("https://x"), workDir, new SubtitleKeywords(List.of()), true));
    }

    @Test
    void cleanNullInputThrows() {
        SubtitleCleanupCoordinator s = service();
        assertThrows(IllegalArgumentException.class, () -> s.clean(null, workDir, new SubtitleKeywords(List.of()), true));
    }

    @Test
    void cleanRejectsNullListener() {
        SubtitleCleanupCoordinator s = service();
        assertThrows(IllegalArgumentException.class,
                () -> s.clean(new MediaInput.RemoteUrl("https://x"), workDir, new SubtitleKeywords(List.of()), null, true));
    }

    // ------------------------------------------------------------------
    // 存量产物兼容（命名去路线化之前落的检查点）
    // ------------------------------------------------------------------

    @Test
    void readsCheckpointThatStillCarriesTheRemovedRouteKey() throws Exception {
        // 去路线化之前落的原始检查点里多一个 route 键（那时的 SubtitleTrack 有 route 字段）。
        // record 去掉字段后，多出来的键必须被静默忽略——不成立的话 readOriginal 会抛 ISE，
        // 整条清洗硬失败，代价与「当未完成重跑」完全不同。
        // 钉的是「我们的代码能读回旧文件」，不是 Jackson 的默认值本身（V1 已实测：容器注入的
        // JsonMapper 与 new ObjectMapper() 都忽略未知键，两侧一致）。
        Files.writeString(workDir.resolve(SubtitleArtifactSpec.ORIGINAL_SEGMENTS.getFileName(null)),
                "{\"route\":\"ASR_CLOUD\",\"language\":\"zh\","
                        + "\"segments\":[{\"beginTimeMs\":0,\"endTimeMs\":5000,"
                        + "\"text\":\"我防关这边露出\"}],\"keywordsFingerprint\":null}");
        SubtitleCleanupCoordinator s = service();

        CleanedSubtitle cleaned = s.clean(new MediaInput.RemoteUrl("https://x"), workDir, new SubtitleKeywords(List.of()), true);

        assertEquals(1, cleaned.segmentCount(), "带 route 键的旧检查点应被正常读出来");
    }

    // ------------------------------------------------------------------
    // 进度事件（管线通过 listener 拿阶段边界与批级进度）
    // ------------------------------------------------------------------

    @Test
    void cleanWithListenerEmitsBeginBatchAndEndEvents() throws Exception {
        writeRawTrack();
        // 真实批级事件来自 SubtitleCleanser（自报 0..100）；本层验「批级换算进 clean 区间 + 首尾事件齐全」
        doAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(3);
            listener.onProgress(new StageProgress(SubtitleStageName.CLEAN_SUBTITLES.getStageName(), 50, "第 1/1 批",
                    StageProgress.State.RUNNING));
            List<TranscriptionSegment> in = invocation.getArgument(0);
            return new CleanseResult(in, 0, 0, 0, 0);
        }).when(subtitleCleanser).clean(any(), any(), any(), any());
        List<StageProgress> events = new ArrayList<>();

        service().clean(new MediaInput.RemoteUrl("https://x"), workDir, new SubtitleKeywords(List.of()), events::add, true);

        assertEquals(3, events.size(), () -> "应发「开始 → 批 → 完成」三条：" + events);
        assertEquals(SubtitleStageName.CLEAN_SUBTITLES.getStageName(), events.get(0).stage());
        assertEquals(70, events.get(0).progress(), "开始 = clean 区间起点");
        assertEquals(StageProgress.State.RUNNING, events.get(0).state());
        assertEquals("第 1/1 批", events.get(1).message(), "批级消息原样透传");
        assertEquals(85, events.get(1).progress(), "批级自报 50 应换算进 [70,100)：70 + 50*30/100 = 85");
        assertEquals(100, events.get(2).progress(), "完成 = clean 区间终点");
        assertEquals(StageProgress.State.SUCCEEDED, events.get(2).state());
    }

    @Test
    void cleanCheckpointHitEmitsSingleSkippedEvent() throws Exception {
        writeRawTrack();
        SubtitleKeywords keywords = new SubtitleKeywords(List.of());
        SubtitleCleanupCoordinator s = service();
        s.clean(new MediaInput.RemoteUrl("https://x"), workDir, keywords, true);

        List<StageProgress> events = new ArrayList<>();
        s.reuseIfAvailable(workDir, keywords, true, events::add);

        assertEquals(1, events.size(), () -> "断点复用只发一条 SKIPPED：" + events);
        assertEquals(StageProgress.State.SKIPPED, events.get(0).state());
        assertEquals(100, events.get(0).progress());
        assertEquals(SubtitleStageName.CLEAN_SUBTITLES.getStageName(), events.get(0).stage());
    }

    @Test
    void cleanMissingRawStillEmitsBeginEventBeforeFailing() {
        // 失败也要先留下「阶段已开始」的痕迹：失败消息才能带上所在阶段（管线的复合监听先落库）
        List<StageProgress> events = new ArrayList<>();

        assertThrows(IllegalStateException.class,
                () -> service().clean(new MediaInput.RemoteUrl("https://x"), workDir, new SubtitleKeywords(List.of()), events::add, true));

        assertEquals(1, events.size(), () -> "失败前应已发「开始清洗」：" + events);
        assertEquals(SubtitleStageName.CLEAN_SUBTITLES.getStageName(), events.get(0).stage());
        assertEquals(StageProgress.State.RUNNING, events.get(0).state());
    }

    // ------------------------------------------------------------------
    // 上下文缓存（design §3.2 / 决策 5）
    // ------------------------------------------------------------------

    private Path contextFile() {
        return workDir.resolve(SubtitleArtifactSpec.CONTEXT.getFileName(null));
    }

    /** 清洗检查点（-clean 的 segments JSON）。 */
    private Path cleanFile() {
        return workDir.resolve(SubtitleArtifactSpec.CLEAN_SEGMENTS.getFileName(null));
    }

    /** 原始转写检查点——重跑的输入，任何路径都不许删它。 */
    private Path rawFile() {
        return workDir.resolve(SubtitleArtifactSpec.ORIGINAL_SEGMENTS.getFileName(null));
    }

    private Path translateSegFile() {
        return workDir.resolve(SubtitleArtifactSpec.TRANSLATED_SEGMENTS.getFileName("en"));
    }

    private Path translateSrtFile() {
        return workDir.resolve(SubtitleArtifactSpec.TRANSLATED_SRT.getFileName("en"));
    }

    private Path termsFile() {
        return workDir.resolve(SubtitleArtifactSpec.TERMS.getFileName("en"));
    }

    @Test
    void summarizeSuccessWritesContextFile() throws Exception {
        writeRawTrack();
        // 用一份有辨识度的上下文当「成功结果」，好钉住落盘的就是它、不是空壳
        when(contextSummarizer.summarize(any(), any()))
                .thenReturn(Optional.of(new SubtitleContext("中世纪剑术", "de", List.of())));
        SubtitleCleanupCoordinator s = service();

        s.clean(new MediaInput.RemoteUrl("https://x"), workDir, new SubtitleKeywords(List.of()), true);

        assertTrue(Files.isRegularFile(contextFile()) && Files.size(contextFile()) > 0,
                "摘要成功应把上下文落盘");
        SubtitleContext persisted = JSON.readValue(contextFile().toFile(), SubtitleContext.class);
        assertEquals("de", persisted.sourceLanguage(), "落盘的该是成功那份上下文");
        assertEquals("中世纪剑术", persisted.theme());
        verify(contextSummarizer).summarize(any(), any());
    }

    @Test
    void contextCacheHitSkipsSummarize() throws Exception {
        writeRawTrack();
        // 摘要缓存可用的前提是「旧 -clean 就是当前这份关键词生成的」——只有重生成这条路会走到它
        SubtitleKeywords keywords = new SubtitleKeywords(List.of());
        writeCleanTrack(keywords.computeFingerprint());
        JSON.writeValue(contextFile().toFile(),
                new SubtitleContext("中世纪剑术", "de", List.of()));
        SubtitleCleanupCoordinator s = service();

        s.regenerate(new MediaInput.RemoteUrl("https://x"), workDir, keywords, ProgressListener.noop());

        verify(contextSummarizer, never()).summarize(any(), any());
        assertEquals(1, cleanserCalls.get(), "指纹没变时不必重新摘要，但仍要重跑清洗");
    }

    @Test
    void fingerprintMismatchForcesResummarize() throws Exception {
        writeRawTrack();
        // 旧 -clean 是另一份关键词生成的：上下文可能也是那份下产的 → 必须重新摘要
        writeCleanTrack("0000deadbeef");
        JSON.writeValue(contextFile().toFile(),
                new SubtitleContext("中世纪剑术", "de", List.of()));
        SubtitleCleanupCoordinator s = service();

        s.regenerate(new MediaInput.RemoteUrl("https://x"), workDir, new SubtitleKeywords(List.of("影视飓风")),
                ProgressListener.noop());

        verify(contextSummarizer).summarize(any(), any());
    }

    @Test
    void cleanSkipsCheckpointOnlyWhenFingerprintMatches() throws Exception {
        writeRawTrack();
        SubtitleKeywords keywords = new SubtitleKeywords(List.of("影视飓风"));
        writeCleanTrack(keywords.computeFingerprint());
        SubtitleCleanupCoordinator s = service();

        assertTrue(s.reuseIfAvailable(workDir, keywords, true, ProgressListener.noop()).isPresent(),
                "指纹一致就该复用现成产物，一次 LLM 都不发");
        assertEquals(0, cleanserCalls.get());
    }

    @Test
    void cleanRerunsWhenFingerprintDiffers() throws Exception {
        writeRawTrack();
        writeCleanTrack("0000deadbeef");
        SubtitleCleanupCoordinator s = service();
        SubtitleKeywords keywords = new SubtitleKeywords(List.of("影视飓风"));

        assertTrue(s.reuseIfAvailable(workDir, keywords, true, ProgressListener.noop()).isEmpty(),
                "关键词变了，旧字幕对不上，不能复用");

        s.clean(new MediaInput.RemoteUrl("https://x"), workDir, keywords, true);
        assertEquals(1, cleanserCalls.get(), "关键词变了，必须重跑");
    }

    @Test
    void regenerateRerunsEvenWhenFingerprintUnchanged() throws Exception {
        writeRawTrack();
        SubtitleKeywords keywords = new SubtitleKeywords(List.of());
        writeCleanTrack(keywords.computeFingerprint());
        SubtitleCleanupCoordinator s = service();
        List<StageProgress> events = new ArrayList<>();

        s.regenerate(new MediaInput.RemoteUrl("https://x"), workDir, keywords, events::add);

        assertEquals(1, cleanserCalls.get(), "用户点了重新生成就是要一份新的，即便关键词没变");
        assertEquals("开始重新生成字幕", events.get(0).message(),
                "开始事件的文案由清洗意图决定，不再靠 requireContextSummary 反推");
    }

    @Test
    void regenerateWritesFingerprintIntoCleanArtifact() throws Exception {
        writeRawTrack();
        SubtitleKeywords keywords = new SubtitleKeywords(List.of("影视飓风"));
        SubtitleCleanupCoordinator s = service();

        s.regenerate(new MediaInput.RemoteUrl("https://x"), workDir, keywords, ProgressListener.noop());

        SubtitleTrack written = JSON.readValue(
                workDir.resolve(SubtitleArtifactSpec.CLEAN_SEGMENTS.getFileName(null)).toFile(),
                SubtitleTrack.class);
        assertEquals(keywords.computeFingerprint(), written.keywordsFingerprint(),
                "产物里必须记着生成它的指纹，否则下次没法判该不该重跑");
    }

    @Test
    void regenerateFailsWithoutTouchingAnythingWhenSummaryDegrades() throws Exception {
        writeRawTrack();
        when(contextSummarizer.summarize(any(), any())).thenReturn(Optional.empty());
        SubtitleCleanupCoordinator s = service();

        assertThrows(IllegalStateException.class,
                () -> s.regenerate(new MediaInput.RemoteUrl("https://x"), workDir, new SubtitleKeywords(List.of()),
                        ProgressListener.noop()));

        assertEquals(0, cleanserCalls.get(), "摘不出上下文就不该硬着头皮清洗");
        assertFalse(Files.exists(cleanFile()), "整体失败时不该留下半成品");
        assertFalse(Files.exists(contextFile()), "重新生成不落降级标记，失败就是失败");
    }

    @Test
    void regenerateFailureKeepsOldCleanAndTranslatedArtifacts() throws Exception {
        // 失败形态之一（摘要失败）：旧字幕与旧译文必须一个都不少——
        // 用户从「能用」掉到「什么都没有」是这套流程最不能接受的后果
        writeRawTrack();
        writeCleanTrack("0000deadbeef");
        Files.writeString(translateSegFile(), "{}");
        Files.writeString(translateSrtFile(), "1\n");
        Files.writeString(termsFile(), "{}");
        when(contextSummarizer.summarize(any(), any())).thenReturn(Optional.empty());
        SubtitleCleanupCoordinator s = service();

        assertThrows(IllegalStateException.class,
                () -> s.regenerate(new MediaInput.RemoteUrl("https://x"), workDir, new SubtitleKeywords(List.of()),
                        ProgressListener.noop()));

        assertTrue(Files.exists(cleanFile()), "旧 -clean 一个字节都不能少");
        assertTrue(Files.exists(translateSegFile()) && Files.exists(translateSrtFile()),
                "失败路径不许删译文——删了用户就同时失去字幕和译文");
        assertTrue(Files.exists(termsFile()), "terms 同样是派生产物，失败时谁都不许动");
        assertTrue(Files.exists(rawFile()), "原始转写是重跑的输入，任何路径都不许删");
    }

    @Test
    void regenerateLeavesTranslatedAndTermsArtifactsToTheStore() throws Exception {
        // 本类只管写文件：译文对不上新字幕了该怎么处置（作废、删文件）归落定产物那一步
        writeRawTrack();
        Files.writeString(translateSegFile(), "{}");
        Files.writeString(translateSrtFile(), "1\n");
        Files.writeString(termsFile(), "{}");
        Files.writeString(contextFile(), "{}");
        SubtitleCleanupCoordinator s = service();

        s.regenerate(new MediaInput.RemoteUrl("https://x"), workDir, new SubtitleKeywords(List.of()), ProgressListener.noop());

        assertTrue(Files.exists(translateSegFile()), "本类不删译文，交给落定产物那一步");
        assertTrue(Files.exists(translateSrtFile()));
        assertTrue(Files.exists(termsFile()));
        assertTrue(Files.exists(cleanFile()), "新 -clean 得留下");
        assertTrue(Files.exists(rawFile()), "原始转写是重跑的输入，谁都不许删");
    }

    @Test
    void summarizeDegradeWritesMarkedContextFile() throws Exception {
        writeRawTrack();
        when(contextSummarizer.summarize(any(), any())).thenReturn(Optional.empty());
        SubtitleCleanupCoordinator s = service();

        CleanedSubtitle cleaned = s.clean(new MediaInput.RemoteUrl("https://x"), workDir, new SubtitleKeywords(List.of()), true);

        assertTrue(Files.isRegularFile(contextFile()), "降级也要落盘，留下「这次试过了」的痕迹");
        SubtitleContext persisted = JSON.readValue(contextFile().toFile(), SubtitleContext.class);
        assertTrue(persisted.degraded(), "落盘的那份必须带降级标记，否则下次会被当成缓存命中");
        assertEquals(2, cleaned.segmentCount(), "摘要失败不该拦住清洗");
        assertEquals(1, cleanserCalls.get(), "清洗照常进行");
    }

    @Test
    void reuseHitLeavesContextFileUntouched() throws Exception {
        writeRawTrack();
        SubtitleKeywords keywords = new SubtitleKeywords(List.of());
        writeCleanTrack(keywords.computeFingerprint());
        // 上次摘要降级留下的标记：复用命中时不许补摘、不许改写它
        JSON.writeValue(contextFile().toFile(), SubtitleContext.degradedEmpty());
        byte[] before = Files.readAllBytes(contextFile());
        SubtitleCleanupCoordinator s = service();

        assertTrue(s.reuseIfAvailable(workDir, keywords, true, ProgressListener.noop()).isPresent());

        verify(contextSummarizer, never()).summarize(any(), any());
        assertArrayEquals(before, Files.readAllBytes(contextFile()),
                "复用命中时 context.json 一个字节都不许动");
        assertEquals(0, cleanserCalls.get());
    }

    @Test
    void keywordsStillReachCleanserWhenSummaryDegrades() throws Exception {
        // AC7 通道②：摘要降级成空上下文时（连关键词也没进摘要器），关键词必须仍原样传给清洗器——
        // 这条独立通道就是为「摘要失败时用户填的词一个字都不生效且毫无提示」而设的
        writeRawTrack();
        when(contextSummarizer.summarize(any(), any())).thenReturn(Optional.empty());
        SubtitleKeywords keywords = new SubtitleKeywords(List.of("影视飓风"));
        SubtitleCleanupCoordinator s = service();

        s.clean(new MediaInput.RemoteUrl("https://x"), workDir, keywords, ProgressListener.noop(), true);

        ArgumentCaptor<SubtitleKeywords> captor = ArgumentCaptor.forClass(SubtitleKeywords.class);
        verify(subtitleCleanser).clean(any(), any(), captor.capture(), any());
        assertEquals(keywords, captor.getValue(), "摘要降级不影响清洗器收到的关键词");
    }
}
