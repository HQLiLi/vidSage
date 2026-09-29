package com.vidsage.service;

import com.vidsage.config.SubtitleCleanupProperties;
import com.vidsage.config.SubtitleTranslateProperties;
import com.vidsage.provider.llm.SubtitleCleanser;
import com.vidsage.provider.llm.SubtitleContextSummarizer;
import com.vidsage.model.asr.TranscriptionSegment;
import com.vidsage.model.media.MediaInput;
import com.vidsage.model.subtitle.CleanedSubtitle;
import com.vidsage.model.subtitle.SubtitleContext;
import com.vidsage.model.subtitle.SubtitleRoute;
import com.vidsage.model.subtitle.SubtitleTrack;
import com.vidsage.provider.asr.ASRProvider;
import com.vidsage.utils.fs.ArtifactFiles;
import com.vidsage.utils.subtitle.SrtRenderer;
import com.vidsage.utils.subtitle.SubtitleArtifactNames;
import com.vidsage.utils.subtitle.SubtitleSegmentSplitter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 字幕清洗编排：把「2b 产出的原始 segments」变成「切分 + LLM 纠错后的 -clean 产物」。
 *
 * <p>管线形状：读原始 segments → 摘要出跨批上下文（{@link SubtitleContextSummarizer}）→
 * {@link SubtitleSegmentSplitter} 切分超长段 → {@link SubtitleCleanser} 逐批清洗 →
 * 写 {@code segments-<route>-clean.json} + {@code subtitles-<route>-clean.srt}。
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
    private final SubtitleTranslateProperties translateProps;

    private final SubtitleContextSummarizer contextSummarizer;

    private final SubtitleTermVerifier termVerifier;

    private final SubtitleCleanser subtitleCleanser;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param workspaceService 工作目录解析
     * @param asrProvider      当前装配的 ASR provider（取路线与产物命名指纹）
     * @param objectMapper     Jackson 3 序列化器（Boot 4 只有 tools.jackson 这份有 bean）
     * @param props            字幕清洗调参（切分预算 / 批次 / 重试 / 相似度阈值）
     * @param translateProps   字幕翻译配置（只取目标语种，判定查证触发条件：源语种 ≠ 目标语种才查）
     * @param contextSummarizer 内容总结器（摘要出主题与术语，供跨批上下文用）
     * @param termVerifier     术语译名查证编排（摘要把术语表定形后、落盘前联网查证官方译名）
     * @param subtitleCleanser 字幕清洗器（持有清洗专用的 ChatClient）
     */
    public SubtitleCleanupService(WorkspaceService workspaceService,
                                  ASRProvider asrProvider,
                                  ObjectMapper objectMapper,
                                  SubtitleCleanupProperties props,
                                  SubtitleTranslateProperties translateProps,
                                  SubtitleContextSummarizer contextSummarizer,
                                  SubtitleTermVerifier termVerifier,
                                  SubtitleCleanser subtitleCleanser) {
        this.workspaceService = workspaceService;
        this.asrProvider = asrProvider;
        this.objectMapper = objectMapper;
        this.props = props;
        this.translateProps = translateProps;
        this.contextSummarizer = contextSummarizer;
        this.termVerifier = termVerifier;
        this.subtitleCleanser = subtitleCleanser;
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
     * 清洗一份字幕：读原始 segments → 摘要出上下文 → 切分 → 逐批清洗 → 写 -clean 产物。
     *
     * @param input     媒体输入；不可为空
     * @param reference 可选参考 / 术语表（纠正误识别术语）；可为 {@code null} 或空白
     * @return 清洗产物（路径 + 语种 + 句数 + 修正/重试统计）
     * @throws IllegalArgumentException input 为空
     * @throws IllegalStateException    原始 segments 缺失（先跑产字幕）、解析或写入失败
     */
    public CleanedSubtitle clean(MediaInput input, String reference) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        SubtitleRoute route = asrProvider.route();
        Path workDir = workspaceService.workspaceFor(input);
        Path rawSeg = workDir.resolve(SubtitleArtifactNames.segmentsFileName(route));
        Path cleanSeg = workDir.resolve(SubtitleArtifactNames.cleanSegmentsFileName(route));
        Path cleanSrt = workDir.resolve(SubtitleArtifactNames.cleanSrtFileName(route));
        SubtitleTrack existing = readCheckpoint(cleanSeg);
        if (existing != null) {
            log.info("字幕清洗断点复用跳过: clean={}", cleanSeg.getFileName());
            // 断点复用不重跑，本趟统计一律为 0（含术语指标），语义与 correctedCount 一致
            return toCleaned(existing, cleanSeg, cleanSrt, 0, 0, 0, 0);
        }

        // 原始缺失：报「缺什么、怎么补」。抛 ISE → ApiExceptionHandler 映射为 500。
        // 语义上更贴 409/400（调用顺序错，非服务端故障），但本轮接受 500：它是「服务端状态缺失」，
        // 可归入 error-handling 的「环境不对」；改成 4xx 需新增异常类或动既有 handler，超本任务
        // 「只新增」范围。2026-09-27 经项目所有者拍板：本轮接受 500，4xx 映射留作后续小任务。
        if (!ArtifactFiles.isNonEmpty(rawSeg)) {
            throw new IllegalStateException("字幕尚未获取：请先调用 POST /api/subtitles 产出原始字幕，再清洗（缺 "
                    + rawSeg.getFileName() + "）");
        }
        // 字幕文件
        SubtitleTrack raw = readRaw(rawSeg);
        // 媒体内容
        List<TranscriptionSegment> segments = raw.segments();
        String radioContent = segments.stream().map(TranscriptionSegment::text).collect(Collectors.joining("\n"));
        // 内容的主题、源语种和从中提取出的术语：先读缓存，没有才摘要。
        // 断点分支留在本层（不在 SubtitleContextSummarizer 里）——与 -clean 断点判据同处，见 design §3.2。
        Path ctxFile = workDir.resolve(SubtitleArtifactNames.contextFileName(route));
        SubtitleContext subtitleContext = ArtifactFiles.readJsonOrNull(ctxFile, objectMapper, SubtitleContext.class);
        if (subtitleContext == null) {
            subtitleContext = contextSummarizer.summarize(radioContent).orElse(null);
            if (subtitleContext != null) {
                // 摘要把术语表定形后、落盘前做一次译名查证（目标语种与源语种相同/配置空白时内部自动跳过）。
                // 查进缓存：断点复用直接命中带 official 的上下文，不重搜。
                SubtitleTermVerifier.Result verification =
                        termVerifier.verifyIfNeeded(subtitleContext, translateProps.targetLanguage());
                subtitleContext = verification.context();
                // 摘要成功才落盘：降级出的空上下文绝不能写文件，否则下次会命中这份空缓存，
                // 摘要永久丢失且不报错（design 决策 5）。
                ArtifactFiles.writeJsonAtomically(subtitleContext, objectMapper, ctxFile);
                log.debug("字幕上下文摘要已落盘: file={}, termVerified={}", ctxFile.getFileName(),
                        verification.changed());
            } else {
                subtitleContext = SubtitleContext.empty();   // 降级，且不写文件
                log.debug("字幕摘要失败，降级为空上下文（不落盘）");
            }
        } else {
            log.debug("字幕上下文缓存命中: file={}", ctxFile.getFileName());
        }

        // 切分
        List<TranscriptionSegment> split = new SubtitleSegmentSplitter(props.maxSegmentMs()).split(segments);

        // 清洗（分批 + 每批带主题/术语/后窗/跨批历史）
        SubtitleCleanser.CleanseResult result = subtitleCleanser.clean(split, subtitleContext, reference);

        SubtitleTrack cleaned = new SubtitleTrack(route, raw.language(), result.segments());
        writeArtifacts(cleanSeg, cleanSrt, cleaned);   // 先 JSON（检查点）再 SRT（派生），都原子替换
        log.info("字幕清洗完成: route={}, segments={}, corrected={}, retry={}, termHits={}, termApplied={}",
                route.name(), result.segments().size(), result.correctedCount(), result.retrySteps(),
                result.termHits(), result.termApplied());
        return toCleaned(cleaned, cleanSeg, cleanSrt, result.correctedCount(), result.retrySteps(),
                result.termHits(), result.termApplied());
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
        ArtifactFiles.writeJsonAtomically(track, objectMapper, cleanSeg);
        ArtifactFiles.writeAtomically(
                SrtRenderer.toSrt(track.segments()).getBytes(StandardCharsets.UTF_8), cleanSrt);
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