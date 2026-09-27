package com.vidsage.service;

import com.vidsage.config.SubtitleCleanupProperties;
import com.vidsage.model.asr.TranscriptionSegment;
import com.vidsage.model.media.MediaInput;
import com.vidsage.model.subtitle.CleanedSubtitle;
import com.vidsage.model.subtitle.SubtitleRoute;
import com.vidsage.model.subtitle.SubtitleTrack;
import com.vidsage.provider.asr.ASRProvider;
import com.vidsage.utils.fs.ArtifactFiles;
import com.vidsage.utils.subtitle.SrtRenderer;
import com.vidsage.utils.subtitle.SubtitleArtifactNames;
import com.vidsage.utils.subtitle.SubtitleCorrector;
import com.vidsage.utils.subtitle.SubtitleSegmentSplitter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 字幕清洗编排：把「2b 产出的原始 segments」变成「切分 + LLM 纠错后的 -clean 产物」。
 *
 * <p>管线形状：读原始 segments → {@link SubtitleSegmentSplitter} 切分超长段 →
 * {@link SubtitleCorrector} 纠错 → 写 {@code segments-<route>-clean.json} + {@code subtitles-<route>-clean.srt}。
 *
 * <p><b>断点复用判据</b>：{@code -clean.json} 存在且字节数 &gt; 0 即跳过切分与纠错，直接反序列化返回。
 * 为什么拿 JSON 当检查点而不是 SRT：空转写产出 0 字节 SRT，拿 SRT 当检查点会让空清洗永远判「未完成」
 * （同 {@link SubtitleService} design 决策 4）。
 *
 * <p><b>不碰 2b 原始产物</b>：读 {@code segments-<route>.json}（原始，只读，必须已存在）、写 {@code -clean}。
 */
@Slf4j
@Service
public class SubtitleCleanupService {

    private final WorkspaceService workspaceService;
    private final ASRProvider asrProvider;
    private final ObjectMapper objectMapper;
    private final SubtitleCleanupProperties props;
    private final SubtitleCorrector.Llm llm;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param workspaceService  工作目录解析
     * @param asrProvider       当前装配的 ASR provider（取路线与产物命名指纹）
     * @param objectMapper      Jackson 3 序列化器（Boot 4 只有 tools.jackson 这份有 bean）
     * @param props             字幕清洗调参（切分预算 / 批次 / 重试 / 相似度阈值）
     * @param llm               LLM 调用口（由 {@code config/SubtitleCleanupConfig} 接 spring-ai ChatModel）
     */
    public SubtitleCleanupService(WorkspaceService workspaceService,
                                  ASRProvider asrProvider,
                                  ObjectMapper objectMapper,
                                  SubtitleCleanupProperties props,
                                  SubtitleCorrector.Llm llm) {
        this.workspaceService = workspaceService;
        this.asrProvider = asrProvider;
        this.objectMapper = objectMapper;
        this.props = props;
        this.llm = llm;
    }

    /**
     * 清洗一份字幕（不提供参考术语表）。
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
     * 清洗一份字幕：读原始 segments → 切分 → 纠错 → 写 -clean 产物。
     *
     * @param input     媒体输入；不可为空
     * @param reference 可选参考 / 术语表（纠正误识别术语）；可为 {@code null} 或空白
     * @return 清洗产物（路径 + 语种 + 句数 + 修正/重试统计）
     * @throws IllegalArgumentException input 为空
     * @throws IllegalStateException    原始 segments 缺失（先跑产字幕）、解析或写入失败
     */
    public CleanedSubtitle clean(MediaInput input, String reference) {
        // 卫语句：早于一切 I/O（error-handling 硬规则）
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }

        SubtitleRoute route = asrProvider.route();
        Path workDir = workspaceService.workspaceFor(input);
        Path rawSeg = workDir.resolve(SubtitleArtifactNames.segmentsFileName(route));
        Path cleanSeg = workDir.resolve(SubtitleArtifactNames.cleanSegmentsFileName(route));
        Path cleanSrt = workDir.resolve(SubtitleArtifactNames.cleanSrtFileName(route));

        // 断点复用：已有非空 -clean.json → 反序列化直接返回，跳过切分与纠错
        SubtitleTrack existing = readCheckpoint(cleanSeg);
        if (existing != null) {
            log.info("字幕清洗断点复用跳过: clean={}", cleanSeg.getFileName());
            return toCleaned(existing, cleanSeg, cleanSrt, 0, 0);
        }

        // 原始缺失：报「缺什么、怎么补」。抛 ISE → ApiExceptionHandler 映射为 500。
        // 语义上更贴 409/400（调用顺序错，非服务端故障），但本轮接受 500：它是「服务端状态缺失」，
        // 可归入 error-handling 的「环境不对」；改成 4xx 需新增异常类或动既有 handler，超本任务
        // 「只新增」范围。2026-09-27 经项目所有者拍板：本轮接受 500，4xx 映射留作后续小任务。
        if (!ArtifactFiles.isNonEmpty(rawSeg)) {
            throw new IllegalStateException("字幕尚未获取：请先调用 POST /api/subtitles 产出原始字幕，再清洗（缺 "
                    + rawSeg.getFileName() + "）");
        }
        SubtitleTrack raw = readRaw(rawSeg);

        // 切分 → 纠错（算法件在方法内 new，拿运行时参数；时间戳只在切分阶段动）
        List<TranscriptionSegment> split =
                new SubtitleSegmentSplitter(props.maxSegmentMs()).split(raw.segments());
        SubtitleCorrector.CorrectionResult corrected = new SubtitleCorrector(
                llm, objectMapper, props.batchSize(), props.maxSteps(),
                props.similarityNormal(), props.similarityShortText())
                .correct(split, reference);

        SubtitleTrack cleaned = new SubtitleTrack(route, raw.language(), corrected.segments());
        writeArtifacts(cleanSeg, cleanSrt, cleaned);   // 先 JSON（检查点）再 SRT（派生），都原子替换
        log.info("字幕清洗完成: route={}, segments={}, corrected={}, retry={}",
                route.name(), corrected.segments().size(),
                corrected.correctedCount(), corrected.retrySteps());
        return toCleaned(cleaned, cleanSeg, cleanSrt,
                corrected.correctedCount(), corrected.retrySteps());
    }

    /**
     * 读断点：{@code -clean.json} 存在且非空则反序列化为 {@link SubtitleTrack}，否则返回 {@code null}。
     * 解析失败当未完成重跑（同 {@link SubtitleService}）。
     */
    private SubtitleTrack readCheckpoint(Path cleanSeg) {
        if (!ArtifactFiles.isNonEmpty(cleanSeg)) {
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
        try {
            ArtifactFiles.writeAtomically(
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(track), cleanSeg);
            ArtifactFiles.writeAtomically(
                    SrtRenderer.toSrt(track.segments()).getBytes(StandardCharsets.UTF_8), cleanSrt);
        } catch (JacksonException e) {
            throw new IllegalStateException("清洗 segments 序列化失败：请检查 SubtitleTrack 结构；原因："
                    + e.getMessage(), e);
        }
    }

    /**
     * 产物 → {@link CleanedSubtitle}：带路径、语种、句数与文件字节数。
     */
    private static CleanedSubtitle toCleaned(SubtitleTrack track, Path cleanSeg, Path cleanSrt,
                                             int correctedCount, int retrySteps) {
        return new CleanedSubtitle(cleanSrt, cleanSeg, track.route(), track.language(),
                track.segments().size(), ArtifactFiles.sizeOrZero(cleanSrt), correctedCount, retrySteps);
    }
}