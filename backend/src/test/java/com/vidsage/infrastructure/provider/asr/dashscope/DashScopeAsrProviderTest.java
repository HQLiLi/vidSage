package com.vidsage.infrastructure.provider.asr.dashscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.dashscope.audio.asr.transcription.Transcription;
import com.alibaba.dashscope.audio.asr.transcription.TranscriptionQueryParam;
import com.alibaba.dashscope.common.Status;
import com.alibaba.dashscope.common.TaskStatus;
import com.alibaba.dashscope.exception.ApiException;
import com.google.gson.JsonObject;
import com.vidsage.domain.transcription.model.AudioSource;
import com.vidsage.domain.transcription.model.TranscriptionCommand;
import com.vidsage.domain.transcription.model.TranscriptionResult;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import com.vidsage.domain.media.model.AudioSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

    /**
     * 造一份配置。只关心鉴权 / 模型的用例用这个，省得每处都写超时值；
     * 轮询超时本身由 {@link #stopsPollingWhenTaskNeverFinishes} 单独覆盖。
     */
    private static DashScopeProperties props(String apiKey) {
        return new DashScopeProperties(apiKey, MODEL, Duration.ofMinutes(15));
    }

    /**
     * 造一份配置并指定转写总时长上限。
     */
    private static DashScopeProperties props(String apiKey, Duration transcriptionTimeout) {
        return new DashScopeProperties(apiKey, MODEL, transcriptionTimeout);
    }

    /**
     * 造一个 SDK 侧的任务状态查询结果。
     */
    private static com.alibaba.dashscope.audio.asr.transcription.TranscriptionResult statusOf(TaskStatus status) {
        var result = new com.alibaba.dashscope.audio.asr.transcription.TranscriptionResult();
        result.setTaskStatus(status);
        return result;
    }

    /**
     * 造一份查询参数（内容不参与断言，只为喂给 awaitTranscription）。
     */
    private static TranscriptionQueryParam query() {
        var param = DashScopeAsrProvider.buildParam(
                command(new AudioSource.AudioUrl("https://example.com/audio.mp3")),
                props("test-key"), "https://example.com/audio.mp3");
        return TranscriptionQueryParam.FromTranscriptionParam(param, "task-1");
    }

    /**
     * 转写任务一直不结束（服务端永远停在 RUNNING）时，必须在总时长上限处抛异常返回，
     * 而不是像 SDK 的 wait 那样无限轮询下去——那会把 run 的互斥键与并发闸门许可永久占住。
     */
    @Test
    void stopsPollingWhenTaskNeverFinishes() {
        var transcription = mock(Transcription.class);
        when(transcription.fetch(any())).thenReturn(statusOf(TaskStatus.RUNNING));
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), props("test-key", Duration.ZERO), (m, p, k) -> "oss://x", transcription);

        var e = assertThrows(IllegalStateException.class,
                () -> provider.awaitTranscription(query(), "task-1"));

        assertTrue(e.getMessage().contains("task-1"),
                "超时信息要带上 taskId（百炼控制台侧的排障锚点）: " + e.getMessage());
    }

    /**
     * 任务正常结束就返回，不多轮询一次。
     */
    @Test
    void returnsAsSoonAsTaskSucceeds() {
        var succeeded = statusOf(TaskStatus.SUCCEEDED);
        var transcription = mock(Transcription.class);
        when(transcription.fetch(any())).thenReturn(succeeded);
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), props("test-key"), (m, p, k) -> "oss://x", transcription);

        assertSame(succeeded, provider.awaitTranscription(query(), "task-1"));
    }

    /**
     * 轮询途中的 503 是服务端瞬时不可用，不该让整次转写失败——下一轮接着问。
     *
     * <p>本用例会真等一个轮询间隔（5 秒）：这条路径的意义就在于「睡完再问」，
     * 用替身把间隔抹掉就等于没测到它。
     */
    @Test
    void keepsPollingThroughTransientServerError() {
        var transcription = mock(Transcription.class);
        when(transcription.fetch(any()))
                .thenThrow(new ApiException(Status.builder().statusCode(503).message("busy").build()))
                .thenReturn(statusOf(TaskStatus.SUCCEEDED));
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), props("test-key", Duration.ofSeconds(30)), (m, p, k) -> "oss://x", transcription);

        assertEquals(TaskStatus.SUCCEEDED,
                provider.awaitTranscription(query(), "task-1").getTaskStatus());
    }

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
                RestClient.builder(), props(""));
        assertThrows(IllegalStateException.class,
                () -> provider.transcribe(command(new AudioSource.AudioUrl("https://example.com/audio.mp3"))));
    }

    @Test
    void requiresAudioUrlValue() {
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), props("test-key"));
        assertThrows(IllegalArgumentException.class,
                () -> provider.resolveCloudAudioUrl(new AudioSource.AudioUrl("")));
    }

    @Test
    void rejectsNullAudioSource() {
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), props("test-key"));
        assertThrows(IllegalArgumentException.class, () -> provider.resolveCloudAudioUrl(null));
    }

    @Test
    void rejectsBlankLocalFilePath() {
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), props("test-key"));
        assertThrows(IllegalArgumentException.class,
                () -> provider.resolveCloudAudioUrl(new AudioSource.AudioFilePath("  ")));
    }

    @Test
    void rejectsMissingLocalFile() {
        var provider = new DashScopeAsrProvider(
                RestClient.builder(), props("test-key"));
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
                RestClient.builder(), props("test-key"), fakeUploader);

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
                RestClient.builder(), props("test-key"), failingUploader);

        Path audio = tempDir.resolve("sample.mp3");
        Files.writeString(audio, "fake audio bytes");

        assertThrows(IllegalStateException.class,
                () -> provider.resolveCloudAudioUrl(new AudioSource.AudioFilePath(audio.toString())));
    }

    @Test
    void addsOssResourceResolveHeaderForTemporaryStorageUrls() {
        var properties = props("test-key");
        String ossUrl = "oss://dashscope-upload/sample.mp3";

        var param = DashScopeAsrProvider.buildParam(
                command(new AudioSource.AudioUrl(ossUrl)), properties, ossUrl);

        assertEquals("enable",
                param.getHeaders().get("X-DashScope-OssResourceResolve"),
                "oss:// temporary storage urls must enable resource resolve");
    }

    @Test
    void omitsOssResourceResolveHeaderForPublicUrls() {
        var properties = props("test-key");
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
                RestClient.builder(), props("test-key"));
        assertEquals(AudioSpec.M4A_AAC_48K_MONO, provider.requiredAudioSpec());
    }

    /**
     * 组装最小合法命令，避免每个用例重复构造。
     */
    private static TranscriptionCommand command(AudioSource source) {
        return new TranscriptionCommand(source, null);
    }
}
