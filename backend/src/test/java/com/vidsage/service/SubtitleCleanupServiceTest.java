package com.vidsage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vidsage.config.SubtitleCleanupProperties;
import com.vidsage.model.asr.TranscriptionSegment;
import com.vidsage.model.media.MediaInput;
import com.vidsage.model.subtitle.CleanedSubtitle;
import com.vidsage.model.subtitle.SubtitleContext;
import com.vidsage.model.subtitle.SubtitleRoute;
import com.vidsage.model.subtitle.SubtitleTrack;
import com.vidsage.provider.asr.ASRProvider;
import com.vidsage.provider.llm.SubtitleCleanser;
import com.vidsage.provider.llm.SubtitleContextSummarizer;
import com.vidsage.utils.subtitle.SubtitleArtifactNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        when(subtitleCleanser.clean(any(), any(), any())).thenAnswer(invocation -> {
            cleanserCalls.incrementAndGet();
            List<TranscriptionSegment> in = invocation.getArgument(0);
            return new SubtitleCleanser.CleanseResult(in, 0, 0, 0, 0);
        });
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
        when(contextSummarizer.summarize(any())).thenReturn(SubtitleContext.empty());
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
}
