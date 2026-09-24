package com.vidsage.asr.provider.dashscope;

import com.alibaba.dashscope.audio.asr.transcription.Transcription;
import com.alibaba.dashscope.audio.asr.transcription.TranscriptionParam;
import com.alibaba.dashscope.audio.asr.transcription.TranscriptionQueryParam;
import com.alibaba.dashscope.common.TaskStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vidsage.asr.ASRProvider;
import com.vidsage.asr.model.AudioSource;
import com.vidsage.asr.model.TranscriptionCommand;
import com.vidsage.asr.model.TranscriptionResult;
import com.vidsage.asr.model.TranscriptionSegment;
import java.net.URI;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 阿里云 DashScope 异步转写供应商（qwen-audio-3.1-asr-flash-filetrans）。
 *
 * <p>「提交任务 -> 轮询状态」由官方 dashscope-sdk-java 的 Transcription
 * 类完成；任务成功后 SDK 返回结果 JSON 的下载地址（24 小时有效），
 * 本类再下载该 JSON 并映射为统一的转写结果契约（句级毫秒时间戳）。
 *
 * <p>SDK 类型只出现在本类内部，对外签名仅使用 asr 包的契约 record。
 * 输入仅支持 {@link AudioSource.AudioUrl}（异步转写接口要求公网可访问地址）。
 */
@Component
@ConditionalOnProperty(name = "vidsage.asr.provider", havingValue = "cloud")
@EnableConfigurationProperties(DashScopeProperties.class)
public class DashScopeAsrProvider implements ASRProvider {

    private final RestClient restClient;
    private final DashScopeProperties properties;
    private final Transcription transcription;

    /**
     * 依赖全部走构造函数注入，保证可测试性（见 quality-guidelines）。
     */
    public DashScopeAsrProvider(RestClient.Builder restClientBuilder, DashScopeProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
        this.transcription = new Transcription();
    }

    /**
     * 执行一次云端异步转写：提交 DashScope 任务并阻塞等待完成
     * （虚拟线程已全局开启，阻塞轮询不占用平台线程），
     * 随后下载结果 JSON 并映射为统一契约。
     *
     * @throws IllegalArgumentException 来源不是 {@link AudioSource.AudioUrl} 或 URL 为空
     */
    @Override
    public TranscriptionResult transcribe(TranscriptionCommand command) {
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new IllegalStateException("Missing DashScope API key: set vidsage.asr.dashscope.api-key or DASHSCOPE_API_KEY");
        }
        if (!(command.source() instanceof AudioSource.AudioUrl audioUrl)) {
            throw new IllegalArgumentException(
                    "DashScope cloud transcription requires a publicly accessible audio URL (AudioUrl); "
                            + "for local files upload them first or pass an AudioUrl. Got: "
                            + (command.source() == null ? "null" : command.source().getClass().getSimpleName()));
        }
        if (!StringUtils.hasText(audioUrl.value())) {
            throw new IllegalArgumentException("audioUrl value must not be blank");
        }

        TranscriptionParam param = buildParam(command, audioUrl.value());
        var submitted = transcription.asyncCall(param);
        if (submitted.getTaskId() == null) {
            throw new IllegalStateException("DashScope did not return a task id");
        }
        var finished = transcription.wait(
                TranscriptionQueryParam.FromTranscriptionParam(param, submitted.getTaskId()));

        var taskResults = finished.getResults();
        if (!TaskStatus.SUCCEEDED.equals(finished.getTaskStatus()) || taskResults.isEmpty()) {
            throw new IllegalStateException("DashScope task did not succeed: " + finished.getTaskStatus());
        }
        var taskResult = taskResults.getFirst();
        if (!TaskStatus.SUCCEEDED.equals(taskResult.getSubTaskStatus())) {
            throw new IllegalStateException("DashScope subtask failed: " + taskResult.getMessage());
        }
        if (!StringUtils.hasText(taskResult.getTranscriptionUrl())) {
            throw new IllegalStateException("DashScope task succeeded without a transcription url");
        }

        TranscriptionFile file = restClient.get()
                .uri(URI.create(taskResult.getTranscriptionUrl()))
                .retrieve()
                .body(TranscriptionFile.class);
        return toResult(file);
    }

    /**
     * 组装提交参数；language_hints 仅在显式指定语种时传递，
     * 语种不确定或多语种混合时留空由模型自动判断。
     */
    private TranscriptionParam buildParam(TranscriptionCommand command, String audioUrl) {
        var builder = TranscriptionParam.builder()
                .model(properties.model())
                .apiKey(properties.apiKey())
                .fileUrls(List.of(audioUrl))
                .channelId(List.of(0));
        if (StringUtils.hasText(command.language())) {
            builder.parameter("language_hints", List.of(command.language()));
        }
        return builder.build();
    }

    /**
     * 将 DashScope 结果 JSON 映射为统一转写契约。
     *
     * <p>包级 static：单元测试无需 Spring 容器即可直接调用（见 quality-guidelines）。
     * 空集合一律用 {@code List.of()}，不返回 {@code null}。
     */
    static TranscriptionResult toResult(TranscriptionFile file) {
        if (file == null || file.transcripts() == null || file.transcripts().isEmpty()) {
            throw new IllegalStateException("DashScope transcription file has no transcripts");
        }
        TranscriptionFile.Transcript transcript = file.transcripts().getFirst();
        List<TranscriptionSegment> segments = transcript.sentences() == null ? List.of()
                : transcript.sentences().stream()
                        .map(sentence -> new TranscriptionSegment(
                                sentence.beginTime(), sentence.endTime(), sentence.text()))
                        .toList();
        return new TranscriptionResult(segments, transcript.language());
    }

    /**
     * DashScope 转写结果 JSON 的响应结构（snake_case 字段用 @JsonProperty 显式映射，
     * ignoreUnknown 防止上游新增字段导致反序列化失败）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TranscriptionFile(
            @JsonProperty("file_url") String fileUrl,
            @JsonProperty("audio_info") AudioInfo audioInfo,
            List<Transcript> transcripts) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record AudioInfo(String format, Integer sampleRate) {
        }

        record Transcript(
                @JsonProperty("channel_id") Integer channelId,
                String text,
                String language,
                List<Sentence> sentences) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Sentence(
                @JsonProperty("sentence_id") Integer sentenceId,
                @JsonProperty("begin_time") Long beginTime,
                @JsonProperty("end_time") Long endTime,
                String text) {
        }
    }
}
