package com.vidsage.service;

import com.vidsage.model.asr.AudioSource;
import com.vidsage.model.asr.TranscriptionResult;
import com.vidsage.model.media.AudioArtifact;
import com.vidsage.model.media.AudioSpec;
import com.vidsage.model.media.MediaInput;
import com.vidsage.model.progress.ProgressListener;
import com.vidsage.model.progress.StageEvent;
import com.vidsage.model.subtitle.SubtitleArtifact;
import com.vidsage.model.subtitle.SubtitleRoute;
import com.vidsage.model.subtitle.SubtitleTrack;
import com.vidsage.provider.asr.ASRProvider;
import com.vidsage.utils.fs.ArtifactFiles;
import com.vidsage.utils.progress.ProgressMapper;
import com.vidsage.utils.subtitle.SrtRenderer;
import com.vidsage.utils.subtitle.SubtitleArtifactNames;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 字幕编排：把「一条 {@link MediaInput}」变成两个产物——{@code subtitles-<路线>.srt} 与
 * {@code segments-<路线>.json}（名字规则见 {@link SubtitleArtifactNames}）。
 *
 * <p>编排顺序（design §四）：取 provider 自述的音频规格 → 断点复用判定 →
 * 音频准备（复用 2a）→ 转写（复用云 provider）→ 组 {@link SubtitleTrack} → 先写 JSON（检查点）
 * 再写 SRT（派生视图）。
 *
 * <p><b>断点复用判据</b>：{@code segments-<路线>.json} 存在且字节数 &gt; 0 即跳过音频准备与转写，
 * 直接反序列化已有 JSON 返回。为什么检查点是 JSON 而不是 SRT：空转写产出 0 字节 SRT，拿 SRT 当
 * 检查点会导致空转写永远判「未完成」→ 反复重跑的死循环（design 决策 4）。文件名规则只有本服务
 * 知道，故复用判据也由本服务判（design §六）。
 *
 * <p>进度权重是<b>过渡态</b>：2a 的 {@code 0..100} 被本服务线性映射进 {@code 0..60}，自己的
 * {@code transcribe} 占 60→95、{@code write-subtitles} 占 95→100。真正形状应以 MVP-1 的
 * SSE payload 定锤，本映射不构成未来约束（design 决策 6）。
 *
 * <p><b>空转写是合法产出</b>：ASR 返回空 segments 时写 0 字节 SRT + 内容正常的 JSON
 * （{@code "segments":[]}），发 SUCCEEDED，记 WARN（design 决策 5）。
 */
@Slf4j
@Service
public class SubtitleService {

    /** 转写阶段名。 */
    static final String STAGE_TRANSCRIBE = "transcribe";

    /** 写字幕阶段名。 */
    static final String STAGE_WRITE_SUBTITLES = "write-subtitles";

    /** 2a 的进度区间映射进本任务的 {@code 0..60}（design 决策 6）。 */
    private static final int ACQUIRE_END = 60;

    /** transcribe 在自己区间（60..95）内的起点权重。 */
    private static final int TRANSCRIBE_START = 60;

    /** transcribe 在自己区间内的终点权重。 */
    private static final int TRANSCRIBE_DONE = 95;

    /** write-subtitles 在自己区间（95..100）内的终点权重。 */
    private static final int WRITE_DONE = 100;

    private final WorkspaceService workspaceService;
    private final AudioPreparationService audioPreparationService;
    private final TranscriptionService transcriptionService;
    private final ASRProvider asrProvider;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param workspaceService          工作目录解析
     * @param audioPreparationService   音频准备（复用 2a，本服务不碰 ffmpeg）
     * @param transcriptionService  转写（复用云 provider）
     * @param asrProvider           当前装配的 ASR provider（用来问规格与路线）
     * @param objectMapper          Jackson 3 序列化器（Boot 4 只有 tools.jackson 这份有 bean）
     */
    public SubtitleService(WorkspaceService workspaceService,
                           AudioPreparationService audioPreparationService,
                           TranscriptionService transcriptionService,
                           ASRProvider asrProvider,
                           ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.audioPreparationService = audioPreparationService;
        this.transcriptionService = transcriptionService;
        this.asrProvider = asrProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 产出一份字幕，不关心进度（内部走 {@link ProgressListener#noop()}）。
     *
     * @param input 媒体输入（URL 或本地文件）
     * @return 两个产物路径与元信息
     */
    public SubtitleArtifact prepare(MediaInput input) {
        return prepare(input, ProgressListener.noop());
    }

    /**
     * 产出一份字幕：要音频 → 转写 → 写两个产物；已有非空 segments JSON 则跳过中间全程。
     *
     * @param input    媒体输入；不可为空
     * @param listener 阶段进度监听；不可为 {@code null}——不关心进度用两参重载
     * @return 字幕产物（SRT 路径 + segments 路径 + 路线 + 语种 + 句数 + SRT 字节数）
     * @throws IllegalArgumentException 输入或监听为空
     * @throws IllegalStateException    媒体获取 / 抽音频 / 转写 / 产物写入失败（由底层原样上抛或包装）
     */
    public SubtitleArtifact prepare(MediaInput input, ProgressListener listener) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        if (listener == null) {
            throw new IllegalArgumentException("进度监听不能为 null：请传一个 ProgressListener，"
                    + "或改用不带 listener 的 prepare(input) 两参重载");
        }

        SubtitleRoute route = asrProvider.route();
        String routeToken = SubtitleArtifactNames.routeToken(route);
        Path workDir = workspaceService.workspaceFor(input);
        Path segmentsPath = workDir.resolve(SubtitleArtifactNames.segmentsFileName(route));
        Path srtPath = workDir.resolve(SubtitleArtifactNames.srtFileName(route));

        // 断点复用：已有非空 segments JSON → 反序列化直接返回，不做任何音频/转写/写盘
        SubtitleTrack existing = readCheckpoint(segmentsPath);
        if (existing != null) {
            log.info("字幕断点复用跳过: segments={}, routeToken={}", segmentsPath.getFileName(), routeToken);
            emit(listener, STAGE_TRANSCRIBE, TRANSCRIBE_DONE, "已有 segments 产物，跳过转写", StageEvent.StageState.SKIPPED);
            emit(listener, STAGE_WRITE_SUBTITLES, WRITE_DONE, "已有字幕产物，跳过写盘", StageEvent.StageState.SKIPPED);
            return toArtifact(srtPath, segmentsPath, existing);
        }

        AudioSpec spec = asrProvider.requiredAudioSpec();
        log.debug("开始准备字幕: workDir={}, routeToken={}, audioSpec={}",
                workDir.getFileName(), routeToken, spec.container());

        // 音频准备 + 转写（2a 的 0..100 映射进 0..60；映射逻辑上提至 ProgressMapper——
        // MVP-1 管线对 clean / translate 的区间映射是第二个使用者）
        AudioArtifact audio = audioPreparationService.prepare(input, spec, ProgressMapper.remap(listener, 0, ACQUIRE_END));
        emit(listener, STAGE_TRANSCRIBE, TRANSCRIBE_START, "开始转写", StageEvent.StageState.RUNNING);
        TranscriptionResult result = transcriptionService.transcribe(
                new AudioSource.AudioFilePath(audio.audioPath().toString()), null);
        emit(listener, STAGE_TRANSCRIBE, TRANSCRIBE_DONE, "转写完成", StageEvent.StageState.SUCCEEDED);

        SubtitleTrack track = new SubtitleTrack(route, result.language(), result.segments());
        if (result.segments().isEmpty()) {
            // 空转写是合法产出：0 字节 SRT + 内容正常（segments:[]）的 JSON
            log.warn("ASR 未识别出任何内容，产出空字幕: segmentsPath={}，可能是纯音乐或无语音音频",
                    segmentsPath.getFileName());
        }
        emit(listener, STAGE_WRITE_SUBTITLES, WRITE_DONE,
                "开始写字幕产物", StageEvent.StageState.RUNNING);
        writeArtifacts(segmentsPath, srtPath, track);
        emit(listener, STAGE_WRITE_SUBTITLES, WRITE_DONE,
                "字幕产物写入完成", StageEvent.StageState.SUCCEEDED);
        return toArtifact(srtPath, segmentsPath, track);
    }

    /**
     * 读断点：segments JSON 存在且非空则反序列化为 {@link SubtitleTrack}，否则返回 {@code null}。
     *
     * <p>只读 JSON，不看 SRT——SRT 是 JSON 的派生视图，即使 SRT 在而 JSON 不在也必须重跑
     * （design 决策 4，回归测试守着这条）。
     */
    private SubtitleTrack readCheckpoint(Path segmentsPath) {
        if (!ArtifactFiles.isNonEmpty(segmentsPath)) {
            return null;
        }
        try {
            return objectMapper.readValue(segmentsPath.toFile(), SubtitleTrack.class);
        } catch (JacksonException e) {
            // 文件在但解析失败：当成没有检查点，重新跑一遍比留一个坏产物好
            // Jackson 3 的 readValue 只抛 JacksonException（RuntimeException），不走 IOException
            log.warn("segments 断点文件解析失败，忽略并按未完成处理: file={}, reason={}",
                    segmentsPath.getFileName(), e.getMessage());
            return null;
        }
    }

    /**
     * 写两个产物：先写 segments JSON（检查点），再写 SRT（由 track 渲染）。两者都原子替换。
     */
    private void writeArtifacts(Path segmentsPath, Path srtPath, SubtitleTrack track) {
        ArtifactFiles.writeJsonAtomically(track, objectMapper, segmentsPath);
        ArtifactFiles.writeAtomically(SrtRenderer.toSrt(track.segments()).getBytes(StandardCharsets.UTF_8), srtPath);
    }

    /**
     * 产物 → {@link SubtitleArtifact}：从磁盘读 SRT 字节数（空转写时为 0）。
     */
    private static SubtitleArtifact toArtifact(Path srtPath, Path segmentsPath, SubtitleTrack track) {
        long srtSizeBytes = ArtifactFiles.sizeOrZero(srtPath);
        return new SubtitleArtifact(srtPath, segmentsPath, track.route(), track.language(),
                track.segments().size(), srtSizeBytes);
    }

    /**
     * 发一个阶段事件。
     */
    private static void emit(ProgressListener listener, String stage, int progress, String message,
                             StageEvent.StageState state) {
        listener.onEvent(new StageEvent(stage, progress, message, state));
    }
}