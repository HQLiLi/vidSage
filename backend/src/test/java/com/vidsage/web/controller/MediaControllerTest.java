package com.vidsage.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vidsage.model.media.AudioArtifact;
import com.vidsage.model.media.AudioContainer;
import com.vidsage.model.media.AudioSpec;
import com.vidsage.model.media.MediaInput;
import com.vidsage.model.process.ExternalToolReport;
import com.vidsage.service.AudioPreparationService;
import com.vidsage.service.ToolDiagnosticsService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MediaController 的切片测试：只装配 web 层（含全局 {@link ApiExceptionHandler}），
 * 服务层用 {@link MockitoBean} 替换，不触发真实诊断与抽音频。
 */
@WebMvcTest(MediaController.class)
class MediaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ToolDiagnosticsService toolDiagnosticsService;

    @MockitoBean
    private AudioPreparationService audioPreparationService;

    @Test
    void returnsToolReports() throws Exception {
        when(toolDiagnosticsService.diagnoseAll()).thenReturn(List.of(
                new ExternalToolReport("yt-dlp", true, "2026.08.19", "F:/tools/yt-dlp.exe", null),
                new ExternalToolReport("ffmpeg", false, null, null,
                        "未找到 ffmpeg：请设置 vidsage.tools.ffmpeg-path 或加入 PATH")));

        // 只断言状态码是不够的：这里断言响应体真能序列化出可读的字段
        mockMvc.perform(get("/api/media/tools"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].toolName").value("yt-dlp"))
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[0].version").value("2026.08.19"))
                .andExpect(jsonPath("$[0].path").value("F:/tools/yt-dlp.exe"))
                .andExpect(jsonPath("$[1].toolName").value("ffmpeg"))
                .andExpect(jsonPath("$[1].available").value(false))
                .andExpect(jsonPath("$[1].hint").value(
                        "未找到 ffmpeg：请设置 vidsage.tools.ffmpeg-path 或加入 PATH"));
    }

    @Test
    void returnsBadRequestWhenBothSourcesGiven() throws Exception {
        mockMvc.perform(post("/api/media/audio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/v","localPath":"F:/media/a.mp4","audioContainer":"WAV"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("url 与 localPath 不能同时传：请恰好传其中一个"));
    }

    @Test
    void returnsBadRequestWhenNoSourceGiven() throws Exception {
        mockMvc.perform(post("/api/media/audio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audioContainer":"WAV"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "缺少媒体来源：请传 url（公网媒体页面地址）或 localPath（服务端可读的本地媒体文件路径）"));
    }

    @Test
    void returnsBadRequestWhenContainerMissing() throws Exception {
        mockMvc.perform(post("/api/media/audio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"localPath":"F:/media/a.mp4"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("音频容器不能为空：请传 audioContainer=WAV 或 M4A"));
    }

    @Test
    void preparesAudioForRemoteUrl() throws Exception {
        Path audioPath = Path.of("F:/works/BV1/audio-16k1ch-t0.wav");
        when(audioPreparationService.prepare(new MediaInput.RemoteUrl("https://example.com/v"),
                AudioSpec.WAV_PCM_16K_MONO))
                .thenReturn(new AudioArtifact(audioPath, AudioSpec.WAV_PCM_16K_MONO, 4096L));

        mockMvc.perform(post("/api/media/audio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/v","audioContainer":"WAV"}"""))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.audioPath").value(audioPath.toString()))
                .andExpect(jsonPath("$.container").value("WAV"))
                .andExpect(jsonPath("$.sampleRateHz").value(16000))
                .andExpect(jsonPath("$.channels").value(1))
                .andExpect(jsonPath("$.bitrateKbps").value(0))
                .andExpect(jsonPath("$.audioTrackIndex").value(0))
                .andExpect(jsonPath("$.sizeBytes").value(4096));
    }

    @Test
    void preparesAudioForLocalPath() throws Exception {
        ArgumentCaptor<MediaInput> inputCaptor = ArgumentCaptor.forClass(MediaInput.class);
        Path audioPath = Path.of("F:/works/local/audio-16k1ch48k-t0.m4a");
        when(audioPreparationService.prepare(inputCaptor.capture(), any(AudioSpec.class)))
                .thenReturn(new AudioArtifact(audioPath, AudioSpec.M4A_AAC_48K_MONO, 2048L));

        mockMvc.perform(post("/api/media/audio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"localPath":"F:/media/a.mp4","audioContainer":"M4A"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.container").value("M4A"))
                .andExpect(jsonPath("$.bitrateKbps").value(48))
                .andExpect(jsonPath("$.sizeBytes").value(2048));

        assertEquals(new MediaInput.LocalPath(Path.of("F:/media/a.mp4")), inputCaptor.getValue());
    }

    @Test
    void mapsNonZeroAudioTrackIndexToSpec() throws Exception {
        ArgumentCaptor<AudioSpec> specCaptor = ArgumentCaptor.forClass(AudioSpec.class);
        Path audioPath = Path.of("F:/works/BV1/audio-16k1ch-t2.wav");
        // 仿真实服务：产物回带调用方传入的规格（service 的契约即「原样回传调用方传入的规格」）
        when(audioPreparationService.prepare(any(MediaInput.class), specCaptor.capture()))
                .thenAnswer(invocation -> new AudioArtifact(audioPath, invocation.getArgument(1), 4096L));

        mockMvc.perform(post("/api/media/audio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/v","audioContainer":"WAV","audioTrackIndex":2}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audioTrackIndex").value(2));

        AudioSpec spec = specCaptor.getValue();
        assertEquals(2, spec.audioTrackIndex());
        assertEquals(AudioContainer.WAV, spec.container());
        assertEquals(16000, spec.sampleRateHz());
        assertEquals(1, spec.channels());
    }

    @Test
    void returnsBadRequestWhenAudioTrackIndexNegative() throws Exception {
        // 这一条精确区分两处检查：边界卫语句（IAE → 400）vs AudioSpec 构造器（ISE → 500）。
        // 若 toAudioSpec 的卫语句漏写，负轨号会落到构造器、以 500 告终，本用例即失败。
        // 故断言的是 400（而非仅「非 200」）——它是判别式的。
        mockMvc.perform(post("/api/media/audio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/v","audioContainer":"WAV","audioTrackIndex":-1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("音频轨序号不能为负：当前为 -1；序号从 0 起计"));

        verify(audioPreparationService, never()).prepare(any(MediaInput.class), any(AudioSpec.class));
    }

    @Test
    void returnsServerErrorWhenDownstreamFails() throws Exception {
        when(audioPreparationService.prepare(any(MediaInput.class), any(AudioSpec.class)))
                .thenThrow(new IllegalStateException("ffmpeg 抽音频失败（exit=1）：boom"));

        mockMvc.perform(post("/api/media/audio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/v","audioContainer":"WAV"}"""))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("ffmpeg 抽音频失败（exit=1）：boom"));
    }
}
