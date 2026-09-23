package com.vidsage.asr;

import com.alibaba.dashscope.audio.asr.transcription.Transcription;
import com.alibaba.dashscope.audio.asr.transcription.TranscriptionParam;
import com.alibaba.dashscope.audio.asr.transcription.TranscriptionQueryParam;
import com.alibaba.dashscope.common.TaskStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 */
@Component
@ConditionalOnProperty(name = "vidsage.asr.provider", havingValue = "cloud")
@EnableConfigurationProperties(DashScopeProperties.class)
public class DashScopeAsrProvider implements ASRProvider {

    private final RestClient restClient;
    private final DashScopeProperties properties;
    private final Transcription transcription;

    public DashScopeAsrProvider(RestClient.Builder restClientBuilder, DashScopeProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
        this.transcription = new Transcription();
    }

    @Override
    public TranscriptionResult transcribe(TranscriptionCommand command) {
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new IllegalStateException("Missing DashScope API key: set vidsage.asr.dashscope.api-key or DASHSCOPE_API_KEY");
        }
        if (!StringUtils.hasText(command.audioUrl())) {
            throw new IllegalArgumentException("audioUrl is required for cloud transcription");
        }

        TranscriptionParam param = buildParam(command);
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

    private TranscriptionParam buildParam(TranscriptionCommand command) {
        var builder = TranscriptionParam.builder()
                .model(properties.model())
                .apiKey(properties.apiKey())
                .fileUrls(List.of(command.audioUrl()))
                .channelId(List.of(0));
        if (StringUtils.hasText(command.language())) {
            builder.parameter("language_hints", List.of(command.language()));
        }
        return builder.build();
    }

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
