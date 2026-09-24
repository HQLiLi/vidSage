package com.vidsage.asr.provider.dashscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vidsage.asr.model.AudioSource;
import com.vidsage.asr.model.TranscriptionCommand;
import com.vidsage.asr.model.TranscriptionResult;
import com.vidsage.asr.model.TranscriptionSegment;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * DashScopeAsrProvider 的纯单元测试：不起 Spring 容器，
 * 映射逻辑直接调包级 static 方法，校验路径直接 new 被测对象。
 */
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
                () -> provider.transcribe(command(new AudioSource.AudioUrl("https://example.com/audio.mp3"))));
    }

    @Test
    void rejectsLocalFilePath() {
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), new DashScopeProperties("test-key", MODEL));
        assertThrows(IllegalArgumentException.class,
                () -> provider.transcribe(command(new AudioSource.AudioFilePath("F:/audio/sample.mp3"))));
    }

    @Test
    void requiresAudioUrlValue() {
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), new DashScopeProperties("test-key", MODEL));
        assertThrows(IllegalArgumentException.class,
                () -> provider.transcribe(command(new AudioSource.AudioUrl(""))));
    }

    /**
     * 组装最小合法命令，避免每个用例重复构造。
     */
    private static TranscriptionCommand command(AudioSource source) {
        return new TranscriptionCommand(source, null);
    }
}