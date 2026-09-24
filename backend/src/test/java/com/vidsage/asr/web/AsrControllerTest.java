package com.vidsage.asr.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vidsage.asr.TranscriptionService;
import com.vidsage.asr.model.AudioSource;
import com.vidsage.asr.model.TranscriptionResult;
import com.vidsage.asr.model.TranscriptionSegment;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AsrController 的切片测试：只装配 web 层，服务层用 @MockitoBean 替换，
 * 不触发真实转写与 Provider 装配。
 */
@WebMvcTest(AsrController.class)
class AsrControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranscriptionService transcriptionService;

    @Test
    void returnsTranscriptionResultForAudioUrl() throws Exception {
        var result = new TranscriptionResult(
                List.of(new TranscriptionSegment(0, 1440, "欢迎使用阿里云。")), "zh");
        when(transcriptionService.transcribe(new AudioSource.AudioUrl("https://example.com/audio.mp3"), "zh"))
                .thenReturn(result);

        mockMvc.perform(post("/api/asr/transcriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audioUrl":"https://example.com/audio.mp3","language":"zh"}"""))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.language").value("zh"))
                .andExpect(jsonPath("$.segments[0].beginTimeMs").value(0))
                .andExpect(jsonPath("$.segments[0].endTimeMs").value(1440))
                .andExpect(jsonPath("$.segments[0].text").value("欢迎使用阿里云。"));
    }

    @Test
    void returnsTranscriptionResultForLocalFilePath() throws Exception {
        var result = new TranscriptionResult(
                List.of(new TranscriptionSegment(0, 1440, "欢迎使用阿里云。")), "zh");
        when(transcriptionService.transcribe(new AudioSource.AudioFilePath("F:/audio/sample.mp3"), null))
                .thenReturn(result);

        mockMvc.perform(post("/api/asr/transcriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audioFilePath":"F:/audio/sample.mp3"}"""))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.language").value("zh"))
                .andExpect(jsonPath("$.segments[0].text").value("欢迎使用阿里云。"));
    }

    @Test
    void returnsBadRequestWhenBothSourcesGiven() throws Exception {
        mockMvc.perform(post("/api/asr/transcriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audioUrl":"https://example.com/audio.mp3","audioFilePath":"F:/audio/sample.mp3"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "audioUrl and audioFilePath are mutually exclusive: pass exactly one of them"));
    }

    @Test
    void returnsBadRequestWhenNoSourceGiven() throws Exception {
        mockMvc.perform(post("/api/asr/transcriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"zh\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "audio source is required: pass audioUrl (publicly accessible URL) or audioFilePath (local file readable by the server)"));
    }
}
