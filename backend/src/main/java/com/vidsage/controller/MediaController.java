package com.vidsage.controller;

import com.vidsage.model.media.AudioArtifact;
import com.vidsage.model.media.AudioContainer;
import com.vidsage.model.media.AudioSpec;
import com.vidsage.model.media.MediaInput;
import com.vidsage.model.process.ExternalToolReport;
import com.vidsage.service.AudioPreparationService;
import com.vidsage.service.ToolDiagnosticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 媒体输入与音频抽取 HTTP 入口：外部工具诊断（只读）+ 同步抽音频。
 *
 * <p><b>临时面（design §七）</b>：{@code POST /api/media/audio} 是<b>同步</b>端点，它存在的唯一
 * 理由是在 MVP-1（任务提交 / 落库 / SSE）尚未落地时，提供一个能被真实调用的端到端入口以产出运行
 * 证据。同步端点在「本地文件 + 短视频」下可用，<b>长视频会顶到 HTTP 客户端超时</b>；真正的形态是
 * MVP-1 的异步管线——<b>该管线落地后，本端点应改为它的一个触发入口或直接删除</b>。
 *
 * <p>因此本控制器<b>不引入任务 ID、不落库、不排队</b>：那些全是 MVP-1 的范围。
 *
 * <p>本类只做「接收 → 校验 → 委托 → 返回」，不写业务逻辑；依赖 {@code service/}，
 * 不直接依赖 {@code provider/}。
 */
@Slf4j
@Tag(name = "媒体与音频", description = "外部工具诊断 + 由 URL 或本地文件抽取音频（同步，临时面）")
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final ToolDiagnosticsService toolDiagnosticsService;
    private final AudioPreparationService audioPreparationService;

    /**
     * 构造函数注入
     *
     * @param toolDiagnosticsService 工具诊断
     * @param audioPreparationService 音频准备编排
     */
    public MediaController(ToolDiagnosticsService toolDiagnosticsService,
                           AudioPreparationService audioPreparationService) {
        this.toolDiagnosticsService = toolDiagnosticsService;
        this.audioPreparationService = audioPreparationService;
    }

    /**
     * 诊断 yt-dlp / ffmpeg / ffprobe：可用性、解析到的绝对路径与版本；yt-dlp 额外报新鲜度。
     *
     * @return 三份工具诊断报告，顺序固定（yt-dlp / ffmpeg / ffprobe）
     */
    @Operation(summary = "诊断外部工具",
            description = "只读、秒级：返回 yt-dlp / ffmpeg / ffprobe 的可用性、解析到的绝对路径与版本；"
                    + "yt-dlp 版本过期时给出新鲜度告警（不自动更新）")
    @GetMapping("/tools")
    public List<ExternalToolReport> tools() {
        long start = System.currentTimeMillis();
        List<ExternalToolReport> reports = toolDiagnosticsService.diagnoseAll();
        log.info("工具诊断完成: available={}/{}, cost={}ms",
                reports.stream().filter(ExternalToolReport::available).count(), reports.size(),
                System.currentTimeMillis() - start);
        return reports;
    }

    /**
     * 由媒体 URL 或本地文件同步抽取音频。
     *
     * @param request 媒体来源（url 与 localPath 只能传一个）、目标容器（必填）与可选音频轨序号
     * @return 音频产物信息（绝对路径 + 展平的规格 + 字节数）
     * @throws IllegalArgumentException url 与 localPath 都传 / 都不传 / 为空白、音频容器缺失或非法、
     *                                  localPath 不是合法路径（均映射为 400）
     * @throws IllegalStateException    外部工具缺失或外部命令未按预期完成（映射为 500）
     */
    @Operation(summary = "由 URL 或本地文件抽取音频",
            description = "同步执行：url 与 localPath 只能传一个；audioContainer 指定 WAV / M4A，"
                    + "audioTrackIndex 指定音频轨（默认 0）。长视频可能顶到客户端超时（临时面，见类注释）")
    @PostMapping("/audio")
    public PreparedAudioResponse prepareAudio(@RequestBody PrepareAudioRequest request) {
        MediaInput input = toMediaInput(request);
        AudioSpec spec = toAudioSpec(request);
        // 入口日志只记「请求形态」：URL 可能带签名查询参数、路径指向用户文件，原值一律不落日志
        log.info("收到抽音频请求: sourceType={}, container={}, audioTrackIndex={}",
                input.getClass().getSimpleName(), spec.container(), spec.audioTrackIndex());
        long start = System.currentTimeMillis();
        // 抽取音频
        AudioArtifact artifact = audioPreparationService.prepare(input, spec);
        log.info("抽音频请求完成: container={}, audioTrackIndex={}, sizeBytes={}, cost={}ms",
                artifact.spec().container(), artifact.spec().audioTrackIndex(), artifact.sizeBytes(),
                System.currentTimeMillis() - start);
        return PreparedAudioResponse.from(artifact);
    }

    /**
     * 请求体 → 媒体输入：{@code url} 与 {@code localPath} <b>必须且只能有一个</b>。
     *
     * @param request 抽音频请求体
     * @return 远程 URL 或本地路径输入
     * @throws IllegalArgumentException 两个都传、都不传或为空白；localPath 不是合法路径
     */
    private static MediaInput toMediaInput(PrepareAudioRequest request) {
        boolean hasUrl = StringUtils.hasText(request.url());
        boolean hasPath = StringUtils.hasText(request.localPath());
        if (hasUrl && hasPath) {
            throw new IllegalArgumentException("url 与 localPath 不能同时传：请恰好传其中一个");
        }
        if (!hasUrl && !hasPath) {
            throw new IllegalArgumentException(
                    "缺少媒体来源：请传 url（公网媒体页面地址）或 localPath（服务端可读的本地媒体文件路径）");
        }
        if (hasUrl) {
            return new MediaInput.RemoteUrl(request.url());
        }
        try {
            return new MediaInput.LocalPath(Path.of(request.localPath()));
        } catch (InvalidPathException e) {
            // Path.of 抛的是携带英文原因的 JDK 异常；这里换成「缺什么 + 怎么补」的中文消息，
            // 与 error-handling.md 的错误信息语言要求一致
            throw new IllegalArgumentException("localPath 不是合法路径：" + request.localPath()
                    + "；请检查路径写法（Windows 下 < > : \" | ? * 为非法字符），或改传 url");
        }
    }

    /**
     * 请求体 → 音频规格：{@code audioContainer} 必填并映射到两个预置规格之一，
     * 再用可选 {@code audioTrackIndex}（缺省沿用预置轨号 0）覆盖音频轨。
     *
     * <p><b>不开放</b>采样率 / 声道 / 码率的自由组合：当前只有两个预置规格在用，开放自由参数
     * 只会引入非法输入面（真要时再加）。
     *
     * @param request 抽音频请求体
     * @return 音频输出规格
     * @throws IllegalArgumentException audioContainer 缺失或不是 WAV / M4A
     */
    private static AudioSpec toAudioSpec(PrepareAudioRequest request) {
        if (!StringUtils.hasText(request.audioContainer())) {
            throw new IllegalArgumentException("音频容器不能为空：请传 audioContainer=WAV 或 M4A");
        }
        AudioContainer container;
        try {
            container = AudioContainer.valueOf(request.audioContainer().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的音频容器：" + request.audioContainer()
                    + "；请传 WAV（PCM，给本地 ASR）或 M4A（AAC，给云端 ASR）");
        }
        AudioSpec preset = switch (container) {
            case WAV -> AudioSpec.WAV_PCM_16K_MONO;
            case M4A -> AudioSpec.M4A_AAC_48K_MONO;
        };
        int trackIndex = request.audioTrackIndex() == null
                ? preset.audioTrackIndex()
                : request.audioTrackIndex();
        return new AudioSpec(preset.container(), preset.sampleRateHz(), preset.channels(),
                preset.bitrateKbps(), trackIndex);
    }

    /**
     * 抽音频请求体。
     *
     * @param url             公网媒体页面地址；与 {@code localPath} 只能传一个
     * @param localPath       服务端可读的本地媒体文件路径；与 {@code url} 只能传一个
     * @param audioContainer  音频容器，必填：{@code WAV} 或 {@code M4A}
     * @param audioTrackIndex 音频轨序号（从 0 起）；<b>不传默认 0</b>，多音轨媒体请显式指定，
     *                        否则抽取实现的默认选轨可能静默取错轨
     */
    @Schema(description = "抽音频请求体：url 与 localPath 只能传一个，audioContainer 必填")
    record PrepareAudioRequest(
            @Schema(description = "公网媒体页面地址；与 localPath 只能传一个", nullable = true,
                    example = "https://www.bilibili.com/video/BV1xx411c7mD")
            String url,
            @Schema(description = "服务端可读的本地媒体文件路径；与 url 只能传一个", nullable = true,
                    example = "F:/media/sample.mp4")
            String localPath,
            @Schema(description = "音频容器，必填", example = "WAV", allowableValues = {"WAV", "M4A"})
            String audioContainer,
            @Schema(description = "音频轨序号（从 0 起）；不传默认 0，多音轨媒体请显式指定",
                    nullable = true, example = "0")
            Integer audioTrackIndex) {
    }

    /**
     * 抽音频响应：由 {@link AudioArtifact} <b>展平映射</b>而来——路径转字符串、规格拆成独立字段。
     *
     * <p><b>为什么不直接复用 {@code AudioArtifact}</b>：它含 {@code java.nio.file.Path}（JSON 形状
     * 取决于 Jackson 对 Path 的支持）与嵌套的 {@code AudioSpec} 契约；直接当 HTTP 形状暴露会把
     * 内部模型的演进绑到对外接口上。展平后每个字段形状明确、可独立加 {@code @Schema}，且不必给
     * {@code model/} 的内部契约补 HTTP 注解。
     *
     * @param audioPath       产物音频文件绝对路径
     * @param container       容器类型（WAV / M4A）
     * @param sampleRateHz    采样率（赫兹）
     * @param channels        声道数
     * @param bitrateKbps     码率（kbps）；无损容器（WAV）为 0
     * @param audioTrackIndex 实际取用的音频轨序号（从 0 起）
     * @param sizeBytes       产物字节数，恒 &gt; 0
     */
    @Schema(description = "音频抽取产物（绝对路径 + 展平的规格 + 字节数）")
    record PreparedAudioResponse(
            @Schema(description = "产物音频文件绝对路径", example = "F:/vidsage/data/works/media-3f2a1b/audio-16k1ch-t0.wav")
            String audioPath,
            @Schema(description = "容器类型", example = "WAV")
            String container,
            @Schema(description = "采样率（赫兹）", example = "16000")
            int sampleRateHz,
            @Schema(description = "声道数", example = "1")
            int channels,
            @Schema(description = "码率（kbps）；WAV 等无损容器为 0", example = "48")
            int bitrateKbps,
            @Schema(description = "实际取用的音频轨序号（从 0 起）", example = "0")
            int audioTrackIndex,
            @Schema(description = "产物字节数，恒大于 0", example = "1048576")
            long sizeBytes) {

        /**
         * 由 {@link AudioArtifact} 映射：路径转字符串、规格展平。
         *
         * @param artifact 抽取产物
         * @return HTTP 响应体
         */
        static PreparedAudioResponse from(AudioArtifact artifact) {
            AudioSpec spec = artifact.spec();
            return new PreparedAudioResponse(
                    artifact.audioPath().toString(),
                    spec.container().name(),
                    spec.sampleRateHz(),
                    spec.channels(),
                    spec.bitrateKbps(),
                    spec.audioTrackIndex(),
                    artifact.sizeBytes());
        }
    }
}
