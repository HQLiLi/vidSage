package com.vidsage.application.subtitle.pipeline;

import com.vidsage.application.media.WorkspaceService;
import com.vidsage.config.SubtitleCleanupProperties;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.StageEvent;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.model.cleanup.CleanedSubtitle;
import com.vidsage.domain.subtitle.model.cleanup.CleanseResult;
import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.subtitle.model.common.SubtitleArtifactSpec;
import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import com.vidsage.domain.subtitle.model.common.SubtitleRoute;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.subtitle.policy.CheckpointPolicy;
import com.vidsage.domain.subtitle.port.SubtitleCleanser;
import com.vidsage.domain.subtitle.port.SubtitleContextSummarizer;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import com.vidsage.domain.transcription.port.ASRProvider;
import com.vidsage.shared.fs.ArtifactFiles;
import com.vidsage.shared.subtitle.SrtRenderer;
import com.vidsage.shared.subtitle.SubtitleSegmentSplitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 字幕清洗编排：把「2b 产出的原始 segments」变成「切分 + LLM 纠错后的 -clean 产物」。
 *
 * <p>管线形状：读原始 segments → 摘要出跨批上下文（{@link SubtitleContextSummarizer}）→
 * {@link SubtitleSegmentSplitter} 切分超长段 → {@link SubtitleCleanser} 逐批清洗 →
 * 写 {@code segments-<route>-clean.json} + {@code subtitles-<route>-clean.srt}。
 *
 * <p><b>断点复用判据</b>：{@code -clean.json} 存在且字节数 &gt; 0 即跳过切分与纠错，直接反序列化返回。
 * 为什么拿 JSON 当检查点而不是 SRT：空转写产出 0 字节 SRT，拿 SRT 当检查点会让空清洗永远判「未完成」
 * ——该语义单源在 {@link CheckpointPolicy}（design 决策 4，同 {@link SubtitlePreparationCoordinator}）。
 *
 * <p><b>不碰 2b 原始产物</b>：读 {@code segments-<route>.json}（原始，只读，必须已存在）、写 {@code -clean}。
 */
@Slf4j
@Component
public class SubtitleCleanupCoordinator {

    /**
     * 阶段进度的起点（0..100 区间自报口径，管线负责 remap 进 70..100）。
     */
    private static final int STAGE_BEGIN = 0;

    /**
     * 阶段进度的终点（0..100 区间自报口径，管线负责 remap 进 70..100）。
     */
    private static final int STAGE_DONE = 100;

    private final WorkspaceService workspaceService;
    private final ASRProvider asrProvider;
    private final ObjectMapper objectMapper;
    private final SubtitleCleanupProperties props;

    private final SubtitleContextSummarizer contextSummarizer;

    private final SubtitleCleanser subtitleCleanser;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param workspaceService  工作目录解析
     * @param asrProvider       当前装配的 ASR provider（取路线与产物命名指纹）
     * @param objectMapper      Jackson 3 序列化器（Boot 4 只有 tools.jackson 这份有 bean）
     * @param props             字幕清洗调参（切分预算 / 批次 / 重试 / 相似度阈值）
     * @param contextSummarizer 内容总结器（摘要出主题与术语，供跨批上下文用）
     * @param subtitleCleanser  字幕清洗器（持有清洗专用的 ChatClient）
     */
    public SubtitleCleanupCoordinator(WorkspaceService workspaceService,
                                      ASRProvider asrProvider,
                                      ObjectMapper objectMapper,
                                      SubtitleCleanupProperties props,
                                      SubtitleContextSummarizer contextSummarizer,
                                      SubtitleCleanser subtitleCleanser) {
        this.workspaceService = workspaceService;
        this.asrProvider = asrProvider;
        this.objectMapper = objectMapper;
        this.props = props;
        this.contextSummarizer = contextSummarizer;
        this.subtitleCleanser = subtitleCleanser;
    }

    /**
     * 清洗一份字幕（不提供参考术语表、不关心进度）。
     *
     * @param input 媒体输入（与产字幕同源，用于定位工作目录）；不可为空
     * @return 清洗产物（-clean SRT + segments 路径 + 统计）
     * @throws IllegalArgumentException input 为空
     * @throws IllegalStateException    原始字幕缺失 / 解析或写入失败
     */
    public CleanedSubtitle clean(MediaInput input) {
        return clean(input, "");
    }

    /**
     * 清洗一份字幕（不关心进度）：内部走 {@link ProgressListener#noop()}。
     *
     * @param input     媒体输入；不可为空
     * @param reference 可选参考 / 术语表（纠正误识别术语）；可为 {@code null} 或空白
     * @return 清洗产物（路径 + 语种 + 句数 + 修正/重试统计）
     * @throws IllegalArgumentException input 为空
     * @throws IllegalStateException    原始 segments 缺失（先跑产字幕）、解析或写入失败
     */
    public CleanedSubtitle clean(MediaInput input, String reference) {
        return clean(input, reference, ProgressListener.noop());
    }

    /**
     * 清洗一份字幕：读原始 segments → 摘要出上下文 → 切分 → 逐批清洗 → 写 -clean 产物。
     *
     * <p>进度事件（{@code 0..100} 自报口径，管线负责 remap 进自己的区间）：
     * <ul>
     *   <li>断点复用命中 → 一条 SKIPPED（进度 100），直接返回；</li>
     *   <li>正常路径 → 开始（0，RUNNING）→ 每批清洗完成各一条（「第 x/N 批」，来自
     *       {@link SubtitleCleanser}）→ 完成（100，SUCCEEDED）。</li>
     * </ul>
     * 开始事件先于「原始字幕缺失」卫语句发出——失败任务的用户消息里能带上所在阶段。
     *
     * @param input     媒体输入；不可为空
     * @param reference 可选参考 / 术语表（纠正误识别术语）；可为 {@code null} 或空白
     * @param listener  进度监听；不可为 {@code null}——不关心进度用两参重载
     * @return 清洗产物（路径 + 语种 + 句数 + 修正/重试统计）
     * @throws IllegalArgumentException input 或 listener 为空
     * @throws IllegalStateException    原始 segments 缺失（先跑产字幕）、解析或写入失败
     */
    public CleanedSubtitle clean(MediaInput input, String reference, ProgressListener listener) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        if (listener == null) {
            throw new IllegalArgumentException("进度监听不能为 null：请传一个 ProgressListener，"
                    + "或改用不带 listener 的 clean(input, reference) 两参重载");
        }
        SubtitleRoute route = asrProvider.route();
        Path workDir = workspaceService.workspaceFor(input);
        Path rawSeg = workDir.resolve(SubtitleArtifactSpec.SEGMENTS_RAW.fileName(route, null));
        Path cleanSeg = workDir.resolve(SubtitleArtifactSpec.SEGMENTS_CLEAN.fileName(route, null));
        Path cleanSrt = workDir.resolve(SubtitleArtifactSpec.SRT_CLEAN.fileName(route, null));
        SubtitleTrack existing = readCheckpoint(cleanSeg);
        if (existing != null) {
            log.info("字幕清洗断点复用跳过: clean={}", cleanSeg.getFileName());
            // 断点复用不重跑，本趟统计一律为 0（含术语指标），语义与 correctedCount 一致

            emit(listener, SubtitleStageName.CLEAN_SUBTITLES.getStageName(), STAGE_DONE, "已有清洗产物，跳过清洗",
                    StageEvent.StageState.SKIPPED);
            return toCleaned(existing, cleanSeg, cleanSrt, 0, 0, 0, 0);
        }
        emit(listener, SubtitleStageName.CLEAN_SUBTITLES.getStageName(), STAGE_BEGIN, "开始清洗", StageEvent.StageState.RUNNING);

        if (!ArtifactFiles.isNonEmpty(rawSeg)) {
            throw new IllegalStateException("字幕尚未获取：请先调用 POST /api/subtitles 产出原始字幕，再清洗（缺 "
                    + rawSeg.getFileName() + "）");
        }
        // 字幕文件
        SubtitleTrack raw = readRaw(rawSeg);
        // 媒体内容
        List<TranscriptionSegment> segments = raw.segments();
        String radioContent = segments.stream().map(TranscriptionSegment::text).collect(Collectors.joining("\n"));
        Path ctxFile = workDir.resolve(SubtitleArtifactSpec.CONTEXT.fileName(route, null));
        SubtitleContext cached = ArtifactFiles.readJsonOrNull(ctxFile, objectMapper, SubtitleContext.class);
        CheckpointPolicy.ContextResolution contextResolution =
                CheckpointPolicy.resolveContext(cached, () -> contextSummarizer.summarize(radioContent));
        SubtitleContext subtitleContext = contextResolution.context();
        switch (contextResolution.source()) {
            // 摘要成功才落盘：降级出的空上下文绝不能写文件，否则下次会命中这份空缓存，
            // 摘要永久丢失且不报错（design 决策 5）。
            case FRESH_SUMMARY -> {
                ArtifactFiles.writeJsonAtomically(subtitleContext, objectMapper, ctxFile);
                log.debug("字幕上下文摘要已落盘: file={}", ctxFile.getFileName());
            }
            case DEGRADED_EMPTY -> log.debug("字幕摘要失败，降级为空上下文（不落盘）");
            case CACHE_HIT -> log.debug("字幕上下文缓存命中: file={}", ctxFile.getFileName());
        }

        // 切分
        List<TranscriptionSegment> split = new SubtitleSegmentSplitter(props.maxSegmentMs()).split(segments);

        // 清洗（分批 + 每批带主题/术语/后窗/跨批历史）；批级进度由 cleanser 直接回调 listener
        CleanseResult result = subtitleCleanser.clean(split, subtitleContext, reference, listener);

        SubtitleTrack cleaned = new SubtitleTrack(route, raw.language(), result.segments());
        writeArtifacts(cleanSeg, cleanSrt, cleaned);   // 先 JSON（检查点）再 SRT（派生），都原子替换
        emit(listener, SubtitleStageName.CLEAN_SUBTITLES.getStageName(), STAGE_DONE, "清洗完成", StageEvent.StageState.SUCCEEDED);
        log.info("字幕清洗完成: route={}, segments={}, corrected={}, retry={}, termHits={}, termApplied={}",
                route.name(), result.segments().size(), result.correctedCount(), result.retrySteps(),
                result.termHits(), result.termApplied());
        return toCleaned(cleaned, cleanSeg, cleanSrt, result.correctedCount(), result.retrySteps(),
                result.termHits(), result.termApplied());
    }

    /**
     * 读断点：{@code -clean.json} 存在且非空则反序列化为 {@link SubtitleTrack}，否则返回 {@code null}。
     * 解析失败当未完成重跑（同 {@link SubtitlePreparationCoordinator}）。
     * 判据语义（JSON 非空 = 完成、0 字节 = 未完成）单源在 {@link CheckpointPolicy}。
     */
    private SubtitleTrack readCheckpoint(Path cleanSeg) {
        if (!CheckpointPolicy.isCompleted(ArtifactFiles.sizeOrZero(cleanSeg))) {
            return null;
        }
        try {
            return objectMapper.readValue(cleanSeg.toFile(), SubtitleTrack.class);
        } catch (JacksonException e) {
            log.warn("-clean 断点解析失败，按未完成重跑: file={}, reason={}", cleanSeg.getFileName(), e.getMessage());
            return null;
        }
    }

    /**
     * 读原始 segments JSON；解析失败抛 ISE（原始若坏，无法清洗）。
     */
    private SubtitleTrack readRaw(Path rawSeg) {
        try {
            return objectMapper.readValue(rawSeg.toFile(), SubtitleTrack.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("原始字幕 segments 解析失败：" + rawSeg.getFileName()
                    + "；原因：" + e.getMessage(), e);
        }
    }

    /**
     * 写两个 -clean 产物：先 segments JSON（检查点），再 SRT（由 track 渲染）。都原子替换。
     */
    private void writeArtifacts(Path cleanSeg, Path cleanSrt, SubtitleTrack track) {
        ArtifactFiles.writeJsonAtomically(track, objectMapper, cleanSeg);
        ArtifactFiles.writeAtomically(
                SrtRenderer.toSrt(track.segments()).getBytes(StandardCharsets.UTF_8), cleanSrt);
    }

    /**
     * 发一个阶段事件（本类阶段进度 0..100 自报口径，区间映射归管线的 {@code ProgressMapper}）。
     */
    private static void emit(ProgressListener listener, String stage, int progress, String message,
                             StageEvent.StageState state) {
        listener.onEvent(new StageEvent(stage, progress, message, state));
    }

    /**
     * 产物 → {@link CleanedSubtitle}：带路径、语种、句数与文件字节数。
     */
    private static CleanedSubtitle toCleaned(SubtitleTrack track, Path cleanSeg, Path cleanSrt,
                                             int correctedCount, int retrySteps,
                                             int termHits, int termApplied) {
        return new CleanedSubtitle(cleanSrt, cleanSeg, track.route(), track.language(),
                track.segments().size(), ArtifactFiles.sizeOrZero(cleanSrt), correctedCount, retrySteps,
                termHits, termApplied);
    }
}