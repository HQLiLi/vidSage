package com.vidsage.asr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class DashScopeAsrProviderTest {

    private static final String MODEL = "qwen-audio-3.1-asr-flash-filetrans";

    @Test
    void mapsSentencesToSegments() {
        var file = new DashScopeAsrProvider.TranscriptionFile(
                "https://example.com/audio.mp3",
                new DashScopeAsrProvider.TranscriptionFile.AudioInfo("mp3", 16000),
                List.of(new DashScopeAsrProvider.TranscriptionFile.Transcript(
                        0,
                        "欢迎使用阿里云。今天天气不错。",
                        "zh",
                        List.of(
                                new DashScopeAsrProvider.TranscriptionFile.Sentence(0, 0L, 1440L, "欢迎使用阿里云。"),
                                new DashScopeAsrProvider.TranscriptionFile.Sentence(1, 1440L, 3000L, "今天天气不错。")))));

        TranscriptionResult result = DashScopeAsrProvider.toResult(file);

        assertEquals("zh", result.language());
        assertEquals(2, result.segments().size());
        assertEquals(new TranscriptionSegment(0, 1440, "欢迎使用阿里云。"), result.segments().get(0));
        assertEquals(new TranscriptionSegment(1440, 3000, "今天天气不错。"), result.segments().get(1));
    }

    @Test
    void throwsWhenTranscriptsMissing() {
        var file = new DashScopeAsrProvider.TranscriptionFile(null, null, List.of());
        assertThrows(IllegalStateException.class, () -> DashScopeAsrProvider.toResult(file));
    }

    @Test
    void requiresApiKey() {
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), new DashScopeProperties("", MODEL));
        assertThrows(IllegalStateException.class,
                () -> provider.transcribe(new TranscriptionCommand("https://example.com/audio.mp3", null)));
    }

    @Test
    void requiresAudioUrl() {
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), new DashScopeProperties("test-key", MODEL));
        assertThrows(IllegalArgumentException.class,
                () -> provider.transcribe(new TranscriptionCommand("", null)));
    }
}
