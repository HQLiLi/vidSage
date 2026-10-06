package com.vidsage.application.subtitle;

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

import com.vidsage.application.media.WorkspaceService;
import com.vidsage.config.SubtitleCleanupProperties;
import com.vidsage.domain.transcription.TranscriptionSegment;
import com.vidsage.domain.media.MediaInput;
import com.vidsage.domain.shared.ProgressListener;
import com.vidsage.domain.shared.StageEvent;
import com.vidsage.domain.subtitle.CleanedSubtitle;
import com.vidsage.domain.subtitle.SubtitleContext;
import com.vidsage.domain.subtitle.SubtitleRoute;
import com.vidsage.domain.subtitle.SubtitleTrack;
import com.vidsage.domain.transcription.ASRProvider;
import com.vidsage.domain.subtitle.CleanseResult;
import com.vidsage.domain.subtitle.SubtitleCleanser;
import com.vidsage.domain.subtitle.SubtitleContextSummarizer;
import com.vidsage.shared.subtitle.SubtitleArtifactNames;
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
import tools.jackson.databind.ObjectMapper;

/**
 * SubtitleCleanupService 的纯单元测试：<b>不起 Spring 容器</b>。
 *
 * <p>只测<b>编排</b>：定位产物 → 断点复用 → 切分 → 交给 {@link SubtitleCleanser} → 写 -clean 产物。
 * 摘要器与 cleanser 都是 mock。
 *
 * <p><b>清洗算法本身（分批、守卫、重试、保留原文）不在这里测</b>——它的覆盖在
 * {@code SubtitleCleanserTest}：真 ChatClient + 桩 ChatModel，桩从 ChatModel 层进去。
 */
class SubtitleCleanupServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SubtitleCleanupProperties PROPS =
            new SubtitleCleanupProperties(8000, 600, 20, 3, 0.7, 0.3, 20000, false);

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final ASRProvider asrProvider = mock(ASRProvider.class);
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

    private SubtitleCleanupService service() {
        when(workspaceService.workspaceFor(any())).thenReturn(workDir);
        when(asrProvider.route()).thenReturn(SubtitleRoute.ASR_CLOUD);
        return new SubtitleCleanupService(workspaceService, asrProvider, JSON, PROPS,
                contextSummarizer, subtitleCleanser);
    }

    private void writeRawTrack() throws Exception {
        SubtitleTrack raw = new SubtitleTrack(SubtitleRoute.ASR_CLOUD, "zh", List.of(
                new TranscriptionSegment(0, 5000, "我防关这边露出"),
                new TranscriptionSegment(5000, 10000, "今天天气不错")));
        JSON.writeValue(workDir.resolve(SubtitleArtifactNames.segmentsFileName(SubtitleRoute.ASR_CLOUD)).toFile(), raw);
    }

    @BeforeEach
    void resetStubs() {
        cleanserCalls.set(0);
        stubCleanserEcho();
        when(contextSummarizer.summarize(any())).thenReturn(Optional.of(SubtitleContext.empty()));
        when(workspaceService.workspaceFor(any())).thenReturn(workDir);
        when(asrProvider.route()).thenReturn(SubtitleRoute.ASR_CLOUD);
    }

    @Test
    void cleanWritesCleanArtifactsAndDelegatesOnce() throws Exception {
        writeRawTrack();
        SubtitleCleanupService s = service();

        CleanedSubtitle cleaned = s.clean(new MediaInput.RemoteUrl("https://x"), "");

        // 两条 -clean 产物都在且非空
        Path cleanSeg = workDir.resolve(SubtitleArtifactNames.cleanSegmentsFileName(SubtitleRoute.ASR_CLOUD));
        Path cleanSrt = workDir.resolve(SubtitleArtifactNames.cleanSrtFileName(SubtitleRoute.ASR_CLOUD));
        assertTrue(Files.isRegularFile(cleanSeg) && Files.size(cleanSeg) > 0, "应产出 -clean.json");
        assertTrue(Files.isRegularFile(cleanSrt) && Files.size(cleanSrt) > 0, "应产出 -clean.srt");
        assertEquals(2, cleaned.segmentCount());
        // 整份清单一次交给 cleanser——内部分几批是它自己的事，service 不该管
        assertEquals(1, cleanserCalls.get(), "整份清单应只交给 cleanser 一次");
    }

    @Test
    void cleanReusesCheckpointSkipsCleanser() throws Exception {
        writeRawTrack();
        SubtitleCleanupService s = service();
        s.clean(new MediaInput.RemoteUrl("https://x"), "");

        // 二跑：-clean.json 已在，应断点复用，不再走切分与清洗
        CleanedSubtitle second = s.clean(new MediaInput.RemoteUrl("https://x"), "");

        assertEquals(1, cleanserCalls.get(), "断点复用后不应再调 cleanser");
        assertEquals(2, second.segmentCount());
    }

    @Test
    void emptyTranscriptionWritesNonEmptyCheckpointDespiteZeroByteSrt() throws Exception {
        // 空转写是合法产出（纯音乐 / 无语音）：raw segments=[]
        SubtitleTrack empty = new SubtitleTrack(SubtitleRoute.ASR_CLOUD, "zh", List.of());
        JSON.writeValue(workDir.resolve(SubtitleArtifactNames.segmentsFileName(SubtitleRoute.ASR_CLOUD)).toFile(), empty);
        SubtitleCleanupService s = service();

        CleanedSubtitle first = s.clean(new MediaInput.RemoteUrl("https://x"), "");

        Path cleanSeg = workDir.resolve(SubtitleArtifactNames.cleanSegmentsFileName(SubtitleRoute.ASR_CLOUD));
        Path cleanSrt = workDir.resolve(SubtitleArtifactNames.cleanSrtFileName(SubtitleRoute.ASR_CLOUD));
        assertEquals(0, first.segmentCount());
        assertEquals(0, first.srtSizeBytes());
        // 关键：SRT 是 0 字节，但检查点 JSON 必须非空——否则下次判「未完成」会反复重跑（死循环）
        assertTrue(Files.size(cleanSeg) > 0, "空转写的 -clean.json 也必须非空，才能当检查点");
        assertEquals(0L, Files.size(cleanSrt), "空转写产出 0 字节 SRT");

        // 二跑：断点命中直接返回，不重跑（空转写不死循环）
        CleanedSubtitle second = s.clean(new MediaInput.RemoteUrl("https://x"), "");
        assertEquals(0, second.segmentCount());
        assertEquals(1, cleanserCalls.get(), "空转写二跑应走断点复用，不再调 cleanser");
    }

    @Test
    void cleanMissingRawThrows() {
        SubtitleCleanupService s = service();
        assertThrows(IllegalStateException.class, () -> s.clean(new MediaInput.RemoteUrl("https://x"), ""));
    }

    @Test
    void cleanNullInputThrows() {
        SubtitleCleanupService s = service();
        assertThrows(IllegalArgumentException.class, () -> s.clean(null, ""));
    }

    @Test
    void cleanRejectsNullListener() {
        SubtitleCleanupService s = service();
        assertThrows(IllegalArgumentException.class,
                () -> s.clean(new MediaInput.RemoteUrl("https://x"), "", null));
    }

    // ------------------------------------------------------------------
    // 进度事件（管线通过 listener 拿阶段边界与批级进度，MVP-1）
    // ------------------------------------------------------------------

    @Test
    void cleanWithListenerEmitsBeginBatchAndEndEvents() throws Exception {
        writeRawTrack();
        // 真实批级事件来自 SubtitleCleanser；本层只验「批级事件原样透传 + 首尾事件齐全」
        doAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(3);
            listener.onEvent(new StageEvent(StageEvent.STAGE_CLEAN, 100, "第 1/1 批",
                    StageEvent.StageState.RUNNING));
            List<TranscriptionSegment> in = invocation.getArgument(0);
            return new CleanseResult(in, 0, 0, 0, 0);
        }).when(subtitleCleanser).clean(any(), any(), any(), any());
        List<StageEvent> events = new ArrayList<>();

        service().clean(new MediaInput.RemoteUrl("https://x"), "", events::add);

        assertEquals(3, events.size(), () -> "应发「开始 → 批 → 完成」三条：" + events);
        assertEquals(StageEvent.STAGE_CLEAN, events.get(0).stage());
        assertEquals(0, events.get(0).progress());
        assertEquals(StageEvent.StageState.RUNNING, events.get(0).state());
        assertEquals("第 1/1 批", events.get(1).message(), "批级事件应原样透传");
        assertEquals(100, events.get(2).progress());
        assertEquals(StageEvent.StageState.SUCCEEDED, events.get(2).state());
    }

    @Test
    void cleanCheckpointHitEmitsSingleSkippedEvent() throws Exception {
        writeRawTrack();
        SubtitleCleanupService s = service();
        s.clean(new MediaInput.RemoteUrl("https://x"), "");

        List<StageEvent> events = new ArrayList<>();
        s.clean(new MediaInput.RemoteUrl("https://x"), "", events::add);

        assertEquals(1, events.size(), () -> "断点复用只发一条 SKIPPED：" + events);
        assertEquals(StageEvent.StageState.SKIPPED, events.get(0).state());
        assertEquals(100, events.get(0).progress());
        assertEquals(StageEvent.STAGE_CLEAN, events.get(0).stage());
    }

    @Test
    void cleanMissingRawStillEmitsBeginEventBeforeFailing() {
        // 失败也要先留下「阶段已开始」的痕迹：失败消息才能带上所在阶段（管线的复合监听先落库）
        List<StageEvent> events = new ArrayList<>();

        assertThrows(IllegalStateException.class,
                () -> service().clean(new MediaInput.RemoteUrl("https://x"), "", events::add));

        assertEquals(1, events.size(), () -> "失败前应已发「开始清洗」：" + events);
        assertEquals(StageEvent.STAGE_CLEAN, events.get(0).stage());
        assertEquals(StageEvent.StageState.RUNNING, events.get(0).state());
    }

    // ------------------------------------------------------------------
    // 上下文缓存（design §3.2 / 决策 5）
    // ------------------------------------------------------------------

    private Path contextFile() {
        return workDir.resolve(SubtitleArtifactNames.contextFileName(SubtitleRoute.ASR_CLOUD));
    }

    @Test
    void summarizeSuccessWritesContextFile() throws Exception {
        writeRawTrack();
        // 用一份有辨识度的上下文当「成功结果」，好钉住落盘的就是它、不是空壳
        when(contextSummarizer.summarize(any()))
                .thenReturn(Optional.of(new SubtitleContext("中世纪剑术", "de", List.of())));
        SubtitleCleanupService s = service();

        s.clean(new MediaInput.RemoteUrl("https://x"), "");

        assertTrue(Files.isRegularFile(contextFile()) && Files.size(contextFile()) > 0,
                "摘要成功应把上下文落盘");
        SubtitleContext persisted = JSON.readValue(contextFile().toFile(), SubtitleContext.class);
        assertEquals("de", persisted.sourceLanguage(), "落盘的该是成功那份上下文");
        assertEquals("中世纪剑术", persisted.theme());
        verify(contextSummarizer).summarize(any());
    }

    @Test
    void contextCacheHitSkipsSummarize() throws Exception {
        writeRawTrack();
        JSON.writeValue(contextFile().toFile(),
                new SubtitleContext("中世纪剑术", "de", List.of()));
        SubtitleCleanupService s = service();

        s.clean(new MediaInput.RemoteUrl("https://x"), "");

        verify(contextSummarizer, never()).summarize(any());
    }

    @Test
    void summarizeDegradeDoesNotWriteContextFile() throws Exception {
        writeRawTrack();
        when(contextSummarizer.summarize(any())).thenReturn(Optional.empty());
        SubtitleCleanupService s = service();

        CleanedSubtitle cleaned = s.clean(new MediaInput.RemoteUrl("https://x"), "");

        assertFalse(Files.exists(contextFile()),
                "降级成空上下文绝不能落盘，否则下次会命中这份空缓存");
        assertEquals(2, cleaned.segmentCount(), "摘要失败不该拦住清洗");
        assertEquals(1, cleanserCalls.get(), "清洗照常进行");
    }
}
