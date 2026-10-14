package com.vidsage.infrastructure.provider.asr.dashscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.dashscope.exception.ApiException;
import com.google.gson.JsonObject;
import com.vidsage.domain.transcription.model.AudioSource;
import com.vidsage.domain.transcription.model.TranscriptionCommand;
import com.vidsage.domain.transcription.model.TranscriptionResult;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import com.vidsage.domain.media.model.AudioSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

/**
 * DashScopeAsrProvider 的纯单元测试：不起 Spring 容器、不发真实网络请求。
 * 映射逻辑直接调包级 static 方法；本地文件上传路径用包级可见的
 * resolveCloudAudioUrl 配合假上传器验证，校验路径直接 new 被测对象。
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
    void requiresAudioUrlValue() {
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), new DashScopeProperties("test-key", MODEL));
        assertThrows(IllegalArgumentException.class,
                () -> provider.resolveCloudAudioUrl(new AudioSource.AudioUrl("")));
    }

    @Test
    void rejectsNullAudioSource() {
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), new DashScopeProperties("test-key", MODEL));
        assertThrows(IllegalArgumentException.class, () -> provider.resolveCloudAudioUrl(null));
    }

    @Test
    void rejectsBlankLocalFilePath() {
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), new DashScopeProperties("test-key", MODEL));
        assertThrows(IllegalArgumentException.class,
                () -> provider.resolveCloudAudioUrl(new AudioSource.AudioFilePath("  ")));
    }

    @Test
    void rejectsMissingLocalFile() {
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), new DashScopeProperties("test-key", MODEL));
        String missing = Path.of("definitely-missing-" + UUID.randomUUID() + ".mp3").toString();
        var e = assertThrows(IllegalArgumentException.class,
                () -> provider.resolveCloudAudioUrl(new AudioSource.AudioFilePath(missing)));
        assertTrue(e.getMessage().contains("不存在"),
                "error should explain what is missing: " + e.getMessage());
    }

    @Test
    void uploadsLocalFileToTemporaryStorage(@TempDir Path tempDir) throws Exception {
        List<String> receivedModels = new ArrayList<>();
        List<String> receivedPaths = new ArrayList<>();
        List<String> receivedKeys = new ArrayList<>();
        DashScopeAsrProvider.TemporaryFileUploader fakeUploader = (model, filePath, apiKey) -> {
            receivedModels.add(model);
            receivedPaths.add(filePath);
            receivedKeys.add(apiKey);
            return "oss://dashscope-upload/" + Path.of(filePath).getFileName();
        };
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), new DashScopeProperties("test-key", MODEL), fakeUploader);

        Path audio = tempDir.resolve("sample.mp3");
        Files.writeString(audio, "fake audio bytes");

        String ossUrl = provider.resolveCloudAudioUrl(new AudioSource.AudioFilePath(audio.toString()));

        assertEquals("oss://dashscope-upload/sample.mp3", ossUrl);
        assertEquals(List.of(MODEL), receivedModels);
        assertEquals(List.of("test-key"), receivedKeys);
        assertEquals(1, receivedPaths.size());
        assertTrue(Path.of(receivedPaths.getFirst()).equals(audio.toRealPath()),
                "uploader should receive the local file path");
    }

    @Test
    void throwsWhenUploadFails(@TempDir Path tempDir) throws Exception {
        DashScopeAsrProvider.TemporaryFileUploader failingUploader =
                (model, filePath, apiKey) -> {
                    throw new ApiException(new RuntimeException("network error"));
                };
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), new DashScopeProperties("test-key", MODEL), failingUploader);

        Path audio = tempDir.resolve("sample.mp3");
        Files.writeString(audio, "fake audio bytes");

        assertThrows(IllegalStateException.class,
                () -> provider.resolveCloudAudioUrl(new AudioSource.AudioFilePath(audio.toString())));
    }

    @Test
    void addsOssResourceResolveHeaderForTemporaryStorageUrls() {
        var properties = new DashScopeProperties("test-key", MODEL);
        String ossUrl = "oss://dashscope-upload/sample.mp3";

        var param = DashScopeAsrProvider.buildParam(
                command(new AudioSource.AudioUrl(ossUrl)), properties, ossUrl);

        assertEquals("enable",
                param.getHeaders().get("X-DashScope-OssResourceResolve"),
                "oss:// temporary storage urls must enable resource resolve");
    }

    @Test
    void omitsOssResourceResolveHeaderForPublicUrls() {
        var properties = new DashScopeProperties("test-key", MODEL);
        String publicUrl = "https://example.com/audio.mp3";

        var param = DashScopeAsrProvider.buildParam(
                command(new AudioSource.AudioUrl(publicUrl)), properties, publicUrl);

        assertTrue(param.getHeaders() == null || param.getHeaders().isEmpty(),
                "public urls must not carry the oss resolve header");
    }

    @Test
    void recognizesNoWordsFailureByErrorCode() {
        var output = new JsonObject();
        output.addProperty("code", "ASR_RESPONSE_HAVE_NO_WORDS");
        assertTrue(DashScopeAsrProvider.isNoWordsFailure(output),
                "output.code == ASR_RESPONSE_HAVE_NO_WORDS must be recognized as empty-worthy failure");
    }

    @Test
    void doesNotTreatOtherFailuresAsNoWords() {
        var otherCode = new JsonObject();
        otherCode.addProperty("code", "SOME_OTHER_ERROR");
        assertFalse(DashScopeAsrProvider.isNoWordsFailure(otherCode));

        var noCode = new JsonObject();
        assertFalse(DashScopeAsrProvider.isNoWordsFailure(noCode));

        assertFalse(DashScopeAsrProvider.isNoWordsFailure(null));
    }

    @Test
    void requiredAudioSpecIsM4a48KMonochannel() {
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), new DashScopeProperties("test-key", MODEL));
        assertEquals(AudioSpec.M4A_AAC_48K_MONO, provider.requiredAudioSpec());
    }

    /**
     * 组装最小合法命令，避免每个用例重复构造。
     */
    private static TranscriptionCommand command(AudioSource source) {
        return new TranscriptionCommand(source, null);
    }
}
