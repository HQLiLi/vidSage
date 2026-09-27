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
import com.vidsage.model.subtitle.SubtitleRoute;
import com.vidsage.model.subtitle.SubtitleTrack;
import com.vidsage.provider.asr.ASRProvider;
import com.vidsage.utils.subtitle.SubtitleArtifactNames;
import com.vidsage.utils.subtitle.SubtitleCorrector;
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
 * SubtitleCleanupService 的纯单元测试：<b>不起 Spring 容器</b>。工作目录与 ASR provider 用 mock，
 * LLM 用桩（按键剧本吐修正后的 JSON），切分器/纠错器是真的——验证编排：切分→纠错→写 -clean、
 * 断点复用、原始缺失报错、空输入卫语句。
 */
class SubtitleCleanupServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SubtitleCleanupProperties PROPS =
            new SubtitleCleanupProperties(8000, 20, 3, 0.7, 0.3);

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final ASRProvider asrProvider = mock(ASRProvider.class);

    /** 记录调用次数、直接吐修正后 JSON 的桩。 */
    private static final class StubLlm implements SubtitleCorrector.Llm {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String complete(String system, String user) {
            calls.incrementAndGet();
            return "{\"1\":\"我防到这边露出\", \"2\":\"今天天气不错\"}";
        }
    }

    private final StubLlm llm = new StubLlm();

    private SubtitleCleanupService service() {
        when(workspaceService.workspaceFor(any())).thenReturn(workDir);
        when(asrProvider.route()).thenReturn(SubtitleRoute.ASR_CLOUD);
        return new SubtitleCleanupService(workspaceService, asrProvider, JSON, PROPS, llm);
    }

    private void writeRawTrack() throws Exception {
        SubtitleTrack raw = new SubtitleTrack(SubtitleRoute.ASR_CLOUD, "zh", List.of(
                new TranscriptionSegment(0, 5000, "我防关这边露出"),
                new TranscriptionSegment(5000, 10000, "今天天气不错")));
        JSON.writeValue(workDir.resolve(SubtitleArtifactNames.segmentsFileName(SubtitleRoute.ASR_CLOUD)).toFile(), raw);
    }

    @BeforeEach
    void cleanStubCount() {
        llm.calls.set(0);
        when(workspaceService.workspaceFor(any())).thenReturn(workDir);
        when(asrProvider.route()).thenReturn(SubtitleRoute.ASR_CLOUD);
    }

    @Test
    void cleanWritesCleanArtifactsAndStats() throws Exception {
        writeRawTrack();
        SubtitleCleanupService s = service();

        CleanedSubtitle cleaned = s.clean(new MediaInput.RemoteUrl("https://x"), "");

        // 两条 -clean 产物都在且非空
        Path cleanSeg = workDir.resolve(SubtitleArtifactNames.cleanSegmentsFileName(SubtitleRoute.ASR_CLOUD));
        Path cleanSrt = workDir.resolve(SubtitleArtifactNames.cleanSrtFileName(SubtitleRoute.ASR_CLOUD));
        assertTrue(Files.isRegularFile(cleanSeg) && Files.size(cleanSeg) > 0, "应产出 -clean.json");
        assertTrue(Files.isRegularFile(cleanSrt) && Files.size(cleanSrt) > 0, "应产出 -clean.srt");
        // 纠错把「防关」改成「防到」，corrected 至少 1 句；句数保持（无切分）
        assertEquals(2, cleaned.segmentCount());
        assertTrue(cleaned.correctedCount() >= 1, "应改到错字句");
        assertEquals(1, llm.calls.get(), "LLM 应只调一次（一批两段）");
    }

    @Test
    void cleanReusesCheckpointSkipsLlm() throws Exception {
        writeRawTrack();
        SubtitleCleanupService s = service();
        s.clean(new MediaInput.RemoteUrl("https://x"), "");

        // 二跑：-clean.json 已在，应断点复用，LLM 不再被调
        CleanedSubtitle second = s.clean(new MediaInput.RemoteUrl("https://x"), "");
        assertEquals(1, llm.calls.get(), "断点复用后 LLM 不应再被调用");
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
        assertEquals(0, llm.calls.get(), "空转写没有批次，LLM 不应被调用");
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