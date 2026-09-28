package com.vidsage.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vidsage.model.subtitle.CleanedSubtitle;
import com.vidsage.model.subtitle.SubtitleArtifact;
import com.vidsage.model.subtitle.SubtitleRoute;
import com.vidsage.service.SubtitleCleanupService;
import com.vidsage.service.SubtitleService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SubtitleController 的切片测试：只装配 web 层，服务层用 @MockitoBean 替换，
 * 不触发真实下载 / 抽音频 / 转写。验证 HTTP 边界的卫语句与响应展平。
 */
@WebMvcTest(SubtitleController.class)
class SubtitleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubtitleService subtitleService;

    @MockitoBean
    private SubtitleCleanupService subtitleCleanupService;

    @Test
    void returnsSubtitleArtifactForUrl() throws Exception {
        when(subtitleService.prepare(any())).thenReturn(new SubtitleArtifact(
                Path.of("F:/work/subtitles-asr-cloud.srt"),
                Path.of("F:/work/segments-asr-cloud.json"),
                SubtitleRoute.ASR_CLOUD, "zh", 2, 88));

        mockMvc.perform(post("/api/subtitles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://www.bilibili.com/video/BV1xx411c7mD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("ASR_CLOUD"))
                .andExpect(jsonPath("$.language").value("zh"))
                .andExpect(jsonPath("$.segmentCount").value(2))
                .andExpect(jsonPath("$.srtSizeBytes").value(88))
                .andExpect(jsonPath("$.srtPath").value("F:\\work\\subtitles-asr-cloud.srt"))
                .andExpect(jsonPath("$.segmentsPath").value("F:\\work\\segments-asr-cloud.json"));
    }

    @Test
    void returnsCleanedSubtitleForUrl() throws Exception {
        when(subtitleCleanupService.clean(any(), any())).thenReturn(new CleanedSubtitle(
                Path.of("F:/work/subtitles-asr-cloud-clean.srt"),
                Path.of("F:/work/segments-asr-cloud-clean.json"),
                SubtitleRoute.ASR_CLOUD, "zh", 2, 159, 1, 0, 3, 2));

        mockMvc.perform(post("/api/subtitles/clean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://www.bilibili.com/video/BV1xx411c7mD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("ASR_CLOUD"))
                .andExpect(jsonPath("$.language").value("zh"))
                .andExpect(jsonPath("$.segmentCount").value(2))
                .andExpect(jsonPath("$.correctedCount").value(1))
                .andExpect(jsonPath("$.retrySteps").value(0))
                .andExpect(jsonPath("$.termHits").value(3))
                .andExpect(jsonPath("$.termApplied").value(2))
                .andExpect(jsonPath("$.srtPath").value("F:\\work\\subtitles-asr-cloud-clean.srt"))
                .andExpect(jsonPath("$.segmentsPath").value("F:\\work\\segments-asr-cloud-clean.json"));
    }

    @Test
    void cleanReturnsBadRequestWhenBothSourcesGiven() throws Exception {
        mockMvc.perform(post("/api/subtitles/clean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://www.bilibili.com/video/av1","localPath":"F:/a.mp4"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("url 与 localPath 不能同时传：请恰好传其中一个"));
    }

    @Test
    void returnsBadRequestWhenBothSourcesGiven() throws Exception {
        mockMvc.perform(post("/api/subtitles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://www.bilibili.com/video/av1","localPath":"F:/a.mp4"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("url 与 localPath 不能同时传：请恰好传其中一个"));
    }

    @Test
    void returnsBadRequestWhenNoSourceGiven() throws Exception {
        mockMvc.perform(post("/api/subtitles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "缺少媒体来源：请传 url（公网媒体页面地址）或 localPath（服务端可读的本地媒体文件路径）"));
    }
}