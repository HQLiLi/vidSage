package com.vidsage.service;

import com.vidsage.model.asr.AudioSource;
import com.vidsage.model.asr.TranscriptionResult;
import com.vidsage.model.asr.TranscriptionSegment;
import com.vidsage.model.media.AudioArtifact;
import com.vidsage.model.media.AudioSpec;
import com.vidsage.model.media.MediaInput;
import com.vidsage.model.progress.ProgressListener;
import com.vidsage.model.progress.StageEvent;
import com.vidsage.model.subtitle.SubtitleArtifact;
import com.vidsage.model.subtitle.SubtitleRoute;
import com.vidsage.model.subtitle.SubtitleTrack;
import com.vidsage.provider.asr.ASRProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 字幕编排：把「一条 {@link MediaInput}」变成两个产物——{@code segments-<路线>.srt} 与
 * {@code segments-<路线>.json}。
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
        String routeToken = routeToken(route);
        Path workDir = workspaceService.workspaceFor(input);
        Path segmentsPath = workDir.resolve(segmentsFileName(route));
        Path srtPath = workDir.resolve(srtFileName(route));

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

        // 音频准备 + 转写（2a 的 0..100 映射进 0..60）
        AudioArtifact audio = audioPreparationService.prepare(input, spec, remap(listener, 0, ACQUIRE_END));
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
        if (!isNonEmpty(segmentsPath)) {
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
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(track);
            writeAtomically(json.getBytes(StandardCharsets.UTF_8), segmentsPath);
            writeAtomically(toSrt(track.segments()).getBytes(StandardCharsets.UTF_8), srtPath);
        } catch (JacksonException e) {
            throw new IllegalStateException("字幕 segments 序列化失败：请检查 SubtitleTrack 结构；"
                    + "原因：" + e.getMessage(), e);
        }
    }

    /**
     * 产物 → {@link SubtitleArtifact}：从磁盘读 SRT 字节数（空转写时为 0）。
     */
    private static SubtitleArtifact toArtifact(Path srtPath, Path segmentsPath, SubtitleTrack track) {
        long srtSizeBytes = nonEmptySize(srtPath);
        return new SubtitleArtifact(srtPath, segmentsPath, track.route(), track.language(),
                track.segments().size(), srtSizeBytes);
    }

    /**
     * 产物可用时的字节数；不存在或为 0 时返回 0。
     */
    private static long nonEmptySize(Path path) {
        try {
            return Files.isRegularFile(path) ? Math.max(Files.size(path), 0) : 0L;
        } catch (IOException e) {
            log.debug("读取产物大小失败，按 0 处理: file={}, reason={}", path.getFileName(), e.getMessage());
            return 0L;
        }
    }

    /**
     * 文件存在且大小 &gt; 0（0 字节视为不存在，用于断点判定）。
     */
    private static boolean isNonEmpty(Path path) {
        return nonEmptySize(path) > 0;
    }

    /**
     * 枚举名 → 产物 token：{@code ASR_CLOUD → asr-cloud}。
     *
     * <p>对照 {@link AudioPreparationService#audioFileName} 从 {@code name()} 派生扩展名——
     * 派生规则只有定名字的人知道，不写进契约类型。token 用 kebab（与 JSON 里的枚举名风格不同
     * 不是疏忽，与 {@code AudioContainer} 同名同源、两种媒材各自的惯例，design 决策 4）。
     *
     * @param route 字幕路线
     * @return kebab 形式的路线 token
     */
    static String routeToken(SubtitleRoute route) {
        return route.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * SRT 产物文件名，带路线指纹：{@code subtitles-asr-cloud.srt}。
     *
     * <p><b>为什么必须带路线指纹</b>：工作目录按媒体内容寻址，同一媒体下唯一会变的就是「谁
     * 转写的」。若叫 {@code subtitles.srt}，换 provider 后重跑会命中上一次云端产物被当成已完成而
     * 跳过，返回与当前配置不符的字幕且不报错。指纹防止跨 provider 误用（design 决策 4）。
     */
    static String srtFileName(SubtitleRoute route) {
        return "subtitles-" + routeToken(route) + ".srt";
    }

    /**
     * segments JSON 产物文件名，带路线指纹：{@code segments-asr-cloud.json}。这是检查点。
     */
    static String segmentsFileName(SubtitleRoute route) {
        return "segments-" + routeToken(route) + ".json";
    }

    /**
     * 毫秒 → SRT 时间戳 {@code HH:MM:SS,mmm}（逗号分隔毫秒，SRT 规范形式）。
     *
     * <p>超过一天的毫秒数会自然进位进「小时」位（{@code 90000000 → 25:00:00,000}），符合 SRT
     * 对长时长的处理。负值钳到 {@code 00:00:00,000}（异常段落防御，见 research/asr-cloud-behavior.md
     * §三；DashScope 正常返回不产生，但钳掉比传出负时间戳稳）。
     */
    static String formatTimestamp(long millis) {
        long safe = Math.max(millis, 0L);
        long hours = safe / 3_600_000L;
        long minutes = (safe % 3_600_000L) / 60_000L;
        long seconds = (safe % 60_000L) / 1_000L;
        long ms = safe % 1_000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, ms);
    }

    /**
     * 渲染整份 SRT（design 决策 7：自己写，不引库）。序号从 1 起，块间空一行，{@code \n} 换行。
     * 句数为 0 时返回空字符串（产出 0 字节文件）。
     */
    static String toSrt(List<TranscriptionSegment> segments) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            TranscriptionSegment s = segments.get(i);
            sb.append(i + 1).append('\n')
                    .append(formatTimestamp(s.beginTimeMs())).append(" --> ")
                    .append(formatTimestamp(s.endTimeMs())).append('\n')
                    .append(s.text() == null ? "" : s.text()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 原子写盘：先写 {@code <name>.tmp} 再原子替换到目标名。
     *
     * <p>与 2a 的 {@link AudioArtifact} 同一条纪律——「路径存在即代表文件完整」。否则断电/崩溃
     * 会留下「非空但被截断」的半成品，而按字节数 &gt; 0 的判据它会被当成已完成（design 决策 7）。
     */
    private static void writeAtomically(byte[] bytes, Path target) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("字幕产物写入失败：" + target
                    + "；请检查磁盘空间与目录写权限。原因：" + e.getMessage(), e);
        }
    }

    /**
     * 把 2a 的 {@code 0..100} 进度线性映射进本任务区间 {@code [from, to]}。
     *
     * <p>2a 的进度是绝对的 {@code 0→50→100}（acquire 与 extract 两段拼接），而本任务要把它压缩
     * 进自己区间的开头（设计决策 6：2a 占 0..60，本任务 transcribe 占 60..95、write 占 95..100）。
     * 因 acquire 结束与 extract 开始都落在 2a 的 50，映射后连续，单调不倒退。
     */
    private static ProgressListener remap(ProgressListener delegate, int from, int to) {
        return event -> {
            int progress = from + (int) ((long) event.progress() * (to - from) / 100L);
            delegate.onEvent(new StageEvent(event.stage(), progress, event.message(), event.state()));
        };
    }

    /**
     * 发一个阶段事件。
     */
    private static void emit(ProgressListener listener, String stage, int progress, String message,
                             StageEvent.StageState state) {
        listener.onEvent(new StageEvent(stage, progress, message, state));
    }
}