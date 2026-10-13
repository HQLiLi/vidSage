package com.vidsage.infrastructure.provider.asr.dashscope;

import com.alibaba.dashscope.audio.asr.transcription.Transcription;
import com.alibaba.dashscope.audio.asr.transcription.TranscriptionParam;
import com.alibaba.dashscope.audio.asr.transcription.TranscriptionQueryParam;
import com.alibaba.dashscope.common.TaskStatus;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.OSSUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.JsonObject;
import com.vidsage.domain.transcription.model.AudioSource;
import com.vidsage.domain.transcription.model.TranscriptionCommand;
import com.vidsage.domain.transcription.model.TranscriptionResult;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import com.vidsage.domain.media.model.AudioSpec;
import com.vidsage.domain.subtitle.model.common.SubtitleRoute;
import com.vidsage.domain.transcription.port.ASRProvider;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>音频来源两种都支持：
 * <ul>
 *   <li>{@link AudioSource.AudioUrl}：公网可访问地址，直接提交；</li>
 *   <li>{@link AudioSource.AudioFilePath}：本地文件，先经 SDK 的
 *       {@link OSSUtils#upload} 上传到百炼临时存储得到 oss:// 地址再提交
 *       （该地址可直接填入 fileUrls）。</li>
 * </ul>
 *
 * <p>SDK 类型只出现在本类内部，对外签名仅使用 asr 包的契约 record。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "vidsage.asr.provider", havingValue = "cloud")
@EnableConfigurationProperties(DashScopeProperties.class)
public class DashScopeAsrProvider implements ASRProvider {

    private final RestClient restClient;
    private final DashScopeProperties properties;
    private final Transcription transcription;
    private final TemporaryFileUploader pathUploader;

    @Autowired
    public DashScopeAsrProvider(RestClient.Builder restClientBuilder, DashScopeProperties properties) {
        this(restClientBuilder, properties, OSSUtils::upload);
    }

    DashScopeAsrProvider(RestClient.Builder restClientBuilder, DashScopeProperties properties,
            TemporaryFileUploader pathUploader) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
        this.transcription = new Transcription();
        this.pathUploader = pathUploader;
    }

    /**
     * 执行一次云端异步转写：把音频来源解析为 filetrans 可接受的地址
     * （URL 直接用；本地文件先上传到百炼临时存储），随后提交 DashScope
     * 任务并阻塞等待完成（虚拟线程已全局开启，阻塞轮询不占用平台线程），
     * 最后下载结果 JSON 并映射为统一契约。
     *
     * @throws IllegalArgumentException URL/路径为空或本地文件不存在、不可读
     * @throws IllegalStateException 配置缺失、上传失败或外部服务未按预期返回
     */
    @Override
    public TranscriptionResult transcribe(TranscriptionCommand command) {
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new IllegalStateException("缺少 DashScope API key：请设置 vidsage.asr.dashscope.api-key 或环境变量 DASHSCOPE_API_KEY");
        }
        String fileUrl = resolveCloudAudioUrl(command.source());
        log.debug("音频地址已解析: {}", describeAddress(fileUrl));

        TranscriptionParam param = buildParam(command, properties, fileUrl);
        long start = System.currentTimeMillis();
        var submitted = transcription.asyncCall(param);
        if (submitted.getTaskId() == null) {
            throw new IllegalStateException("DashScope 未返回 taskId");
        }
        // taskId 是外部系统侧的排障锚点，凭它可在百炼控制台核对任务，必须 INFO 落日志
        log.info("DashScope 转写任务已提交: taskId={}, model={}", submitted.getTaskId(), properties.model());
        var finished = transcription.wait(
                TranscriptionQueryParam.FromTranscriptionParam(param, submitted.getTaskId()));
        log.info("DashScope 转写任务已结束: taskId={}, status={}, cost={}ms",
                submitted.getTaskId(), finished.getTaskStatus(), System.currentTimeMillis() - start);

        var taskResults = finished.getResults();
        // 无语音 / 静音音频：DashScope 返回任务失败（code=ASR_RESPONSE_HAVE_NO_WORDS），
        // 而不是空 segments。对用户来说「这段内容没有语音」是合法输入，不是服务故障——
        // 把它降级成空结果（空转写是合法产出）。
        // 错误码从 output 的 gson JsonObject 结构化取，不用 toString 字符串匹配。
        if (!TaskStatus.SUCCEEDED.equals(finished.getTaskStatus())
                && isNoWordsFailure(finished.getOutput())) {
            log.warn("DashScope 未识别出任何语音（code=ASR_RESPONSE_HAVE_NO_WORDS），"
                    + "返回空转写结果: taskId={}", submitted.getTaskId());
            return new TranscriptionResult(List.of(), null);
        }
        if (!TaskStatus.SUCCEEDED.equals(finished.getTaskStatus()) || taskResults.isEmpty()) {
            String reason = taskResults.stream()
                    .map(result -> result.getMessage())
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse("");
            String output = finished.getOutput() == null ? "" : finished.getOutput().toString();
            throw new IllegalStateException("DashScope 任务未成功：" + finished.getTaskStatus()
                    + (StringUtils.hasText(output) ? "，output：" + output : ""));
        }
        var taskResult = taskResults.getFirst();
        if (!TaskStatus.SUCCEEDED.equals(taskResult.getSubTaskStatus())) {
            throw new IllegalStateException("DashScope 子任务失败：" + taskResult.getMessage());
        }
        if (!StringUtils.hasText(taskResult.getTranscriptionUrl())) {
            throw new IllegalStateException("DashScope 任务成功但未返回 transcriptionUrl");
        }

        log.debug("下载转写结果 JSON: taskId={}", submitted.getTaskId());
        TranscriptionFile file = restClient.get()
                .uri(URI.create(taskResult.getTranscriptionUrl()))
                .retrieve()
                .body(TranscriptionFile.class);
        return toResult(file);
    }

    /**
     * 判定 DashScope 的 output 是否为「没有识别出任何语音」失败。
     *
     * <p>静音音频返回 {@code FAILED} 且
     * output 里 {@code code=ASR_RESPONSE_HAVE_NO_WORDS}。这是「内容合法但无语音」，不是服务故障，
     * 需要降级为空结果而非抛异常。output 是 gson {@link JsonObject}，用 {@code get("code")}
     * 结构化取值，避免 toString 字符串匹配。
     *
     * @param output DashScope 任务的 output 对象，可能为 {@code null}
     * @return 是否为 HAVE_NO_WORDS 失败
     */
    static boolean isNoWordsFailure(JsonObject output) {
        if (output == null) {
            return false;
        }
        var code = output.get("code");
        if (code == null || code.isJsonNull() || !code.isJsonPrimitive()) {
            return false;
        }
        return "ASR_RESPONSE_HAVE_NO_WORDS".equals(code.getAsString());
    }

    /**
     * 本实现要求 16k 单声道 M4A：体积优先（wav 的 1/4～1/8），走上传到百炼临时存储的带宽。
     */
    @Override
    public AudioSpec requiredAudioSpec() {
        return AudioSpec.M4A_AAC_48K_MONO;
    }

    /**
     * 本实现产出云端 DashScope 转写字幕。
     */
    @Override
    public SubtitleRoute route() {
        return SubtitleRoute.ASR_CLOUD;
    }

    /**
     * 把统一的音频来源解析为 DashScope filetrans 接口可接受的音频地址：
     * URL 原样返回；本地文件先上传到百炼临时存储，换成返回的 oss:// 地址。
     *
     * @throws IllegalArgumentException 来源为 null、URL/路径为空或本地文件不存在、不可读
     * @throws IllegalStateException 上传到临时存储失败
     */
    String resolveCloudAudioUrl(AudioSource source) {
        if (source == null) {
            throw new IllegalArgumentException(
                    "音频来源不能为空：请传 AudioUrl（公网 URL）或 AudioFilePath（本地文件）");
        }
        return switch (source) {
            case AudioSource.AudioUrl url -> requirePublicUrl(url);
            case AudioSource.AudioFilePath local -> uploadToTemporaryStorage(local);
        };
    }

    /**
     * 校验 URL 来源非空后原样返回；空值时说明「缺什么、怎么补」。
     */
    private String requirePublicUrl(AudioSource.AudioUrl url) {
        if (!StringUtils.hasText(url.value())) {
            throw new IllegalArgumentException(
                    "audioUrl 取值不能为空白：请传公网可访问的音频 URL");
        }
        return url.value();
    }

    /**
     * 校验本地文件存在且可读后，经 SDK 的 {@link OSSUtils} 上传到百炼临时存储，
     * 返回可填入 fileUrls 的 oss:// 地址。上传失败统一包装为 {@link IllegalStateException}。
     */
    private String uploadToTemporaryStorage(AudioSource.AudioFilePath local) {
        if (!StringUtils.hasText(local.value())) {
            throw new IllegalArgumentException(
                    "audioFilePath 取值不能为空白：请传本地音频文件的绝对或相对路径");
        }
        Path path = Path.of(local.value());
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("本地音频文件不存在：" + path.toAbsolutePath()
                    + "；请检查路径，或改传 audioUrl");
        }
        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException("本地音频文件不可读：" + path.toAbsolutePath()
                    + "；请给服务运行账号授予读权限，或改传 audioUrl");
        }
        // 本地路径在异常信息里已可见，这里打 INFO 是把「上传」与后续「提交/轮询」在时间线上分开
        log.info("上传本地音频到百炼临时存储: file={}", path.toAbsolutePath());
        try {
            String ossUrl = pathUploader.upload(
                    properties.model(), path.toAbsolutePath().toString(), properties.apiKey());
            if (!StringUtils.hasText(ossUrl)) {
                throw new IllegalStateException(
                        "DashScope 上传后未返回 oss:// 地址，文件：" + path.toAbsolutePath());
            }
            return ossUrl;
        } catch (NoApiKeyException | ApiException e) {
            throw new IllegalStateException(
                    "本地音频文件上传到 DashScope 临时存储失败：" + e.getMessage(), e);
        }
    }

    /**
     * 日志用的音频地址描述：只记录形态与主机名，不记录完整地址——
     * 公网音频 URL 常带签名查询参数（如 OSS 的 Signature），整条打印等于把临时凭证写进日志。
     */
    private static String describeAddress(String audioUrl) {
        if (audioUrl.startsWith("oss://")) {
            return "oss-temporary-storage";
        }
        try {
            URI uri = URI.create(audioUrl);
            return uri.getHost() == null ? "unknown-host" : uri.getHost();
        } catch (IllegalArgumentException e) {
            return "unparsable-url";
        }
    }

    /**
     * 组装提交参数；language_hints 仅在显式指定语种时传递，
     * 语种不确定或多语种混合时留空由模型自动判断。
     */
    static TranscriptionParam buildParam(TranscriptionCommand command, DashScopeProperties properties, String audioUrl) {
        var builder = TranscriptionParam.builder()
                .model(properties.model())
                .apiKey(properties.apiKey())
                .fileUrls(List.of(audioUrl))
                .channelId(List.of(0));
        if (audioUrl.startsWith("oss://")) {
            // 百炼临时存储的 oss:// 地址必须显式开启解析开关，
            // 否则 filetrans 服务端报 REQUEST_INVALID_FILE_URL_VALUE（见 docs/prd/local-model-deployment.md）。
            builder.headers(Map.of("X-DashScope-OssResourceResolve", "enable"));
        }
        if (StringUtils.hasText(command.language())) {
            builder.parameter("language_hints", List.of(command.language()));
        }
        return builder.build();
    }

    /**
     * 将 DashScope 结果 JSON 映射为统一转写契约。
     *
     * <p>空集合一律用 {@code List.of()}，不返回 {@code null}。
     */
    static TranscriptionResult toResult(TranscriptionFile file) {
        if (file == null || file.transcripts() == null || file.transcripts().isEmpty()) {
            throw new IllegalStateException("DashScope 转写结果文件中没有任何 transcripts");
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

    /**
     * 本地文件上传策略：与 {@link OSSUtils#upload} 的方法签名对齐
     * （model, filePath, apiKey -> oss:// 地址）。
     */
    @FunctionalInterface
    interface TemporaryFileUploader {

        /**
         * 上传本地文件到百炼临时存储。
         *
         * @param model    目标模型名（getPolicy 请求需要）
         * @param filePath 本地文件绝对路径
         * @param apiKey   DashScope API key
         * @return oss:// 临时地址
         * @throws NoApiKeyException API key 缺失
         * @throws ApiException      上传接口调用失败（RuntimeException）
         */
        String upload(String model, String filePath, String apiKey) throws NoApiKeyException;
    }
}
