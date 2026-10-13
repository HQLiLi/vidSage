package com.vidsage.application.subtitle.pipeline;

import com.vidsage.application.media.WorkspaceKeyGenerator;
import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.config.SubtitleCleanupProperties;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.model.cleanup.CleanedSubtitle;
import com.vidsage.domain.subtitle.model.cleanup.CleanseResult;
import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.subtitle.model.common.SubtitleArtifactSpec;
import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import com.vidsage.domain.subtitle.model.common.SubtitleRoute;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.policy.CheckpointPolicy;
import com.vidsage.domain.subtitle.port.SubtitleCleanser;
import com.vidsage.domain.subtitle.port.SubtitleContextSummarizer;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import com.vidsage.domain.transcription.port.ASRProvider;
import com.vidsage.shared.fs.ArtifactFiles;
import com.vidsage.shared.progress.ProgressMapper;
import com.vidsage.shared.subtitle.SrtRenderer;
import com.vidsage.shared.subtitle.SubtitleSegmentSplitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 字幕清洗编排：把产字幕阶段产出的原始 segments，变成「切分 + LLM 纠错后的 -clean 产物」。
 *
 * <p><b>断点复用靠关键词指纹</b>：{@code -clean.json} 存在、字节数 &gt; 0，且它记着的指纹与当前关键词
 * 一致，才跳过切分与纠错直接返回。摘要缓存的有效性由同一枚指纹决定——{@code -clean} 只在清洗成功后才
 * 落盘，所以「指纹一致」蕴含「当初写的上下文也是这一份」；判不一致就重新摘要，<b>安全方向永远是重跑</b>。
 *
 * <p>检查点语义（为什么是 JSON 而不是 SRT）单源在 {@link CheckpointPolicy}，本类不另判。
 * 只读产字幕阶段的原始产物（{@code segments-<route>.json}，必须已存在），只写 {@code -clean}。
 */
@Slf4j
@Component
public class SubtitleCleanupCoordinator {

    private final WorkspaceKeyGenerator workspaceKeyGenerator;
    private final WorkspaceDirectoryManager workspaceDirectoryManager;
    private final ASRProvider asrProvider;
    private final ObjectMapper objectMapper;
    private final SubtitleCleanupProperties props;

    private final SubtitleContextSummarizer contextSummarizer;

    private final SubtitleCleanser subtitleCleanser;

    /**
     * @param workspaceKeyGenerator     工作区key生成器
     * @param workspaceDirectoryManager 工作区目录管理器
     * @param asrProvider               当前装配的 ASR provider（取路线与产物命名指纹）
     * @param objectMapper              Jackson 3 序列化器（Boot 4 只有 tools.jackson 这份有 bean）
     * @param props                     字幕清洗调参（切分预算 / 批次 / 重试 / 相似度阈值）
     * @param contextSummarizer         内容总结器（摘要出主题与术语，供跨批上下文用）
     * @param subtitleCleanser          字幕清洗器（持有清洗专用的 ChatClient）
     */
    public SubtitleCleanupCoordinator(WorkspaceKeyGenerator workspaceKeyGenerator,
                                      WorkspaceDirectoryManager workspaceDirectoryManager,
                                      ASRProvider asrProvider,
                                      ObjectMapper objectMapper,
                                      SubtitleCleanupProperties props,
                                      SubtitleContextSummarizer contextSummarizer,
                                      SubtitleCleanser subtitleCleanser) {
        this.workspaceKeyGenerator = workspaceKeyGenerator;
        this.workspaceDirectoryManager = workspaceDirectoryManager;
        this.asrProvider = asrProvider;
        this.objectMapper = objectMapper;
        this.props = props;
        this.contextSummarizer = contextSummarizer;
        this.subtitleCleanser = subtitleCleanser;
    }

    /**
     * 清洗一份字幕（不带关键词、不报告进度）。
     *
     * @param input 媒体输入（与产字幕同源，用于定位工作目录）；不可为空
     * @return 清洗产物（-clean SRT + segments 路径 + 统计）
     * @throws IllegalArgumentException input 为空
     * @throws IllegalStateException    原始字幕缺失 / 解析或写入失败
     */
    public CleanedSubtitle clean(MediaInput input) {
        return clean(input, new SubtitleKeywords(List.of()));
    }

    /**
     * 清洗一份字幕（不报告进度）。
     *
     * @param input    媒体输入；不可为空
     * @param keywords 用户手填的关键词（专有名词正确写法，纠正同音误写）；空清单就是「不纠这一类」
     * @return 清洗产物（路径 + 语种 + 句数 + 修正/重试统计）
     * @throws IllegalArgumentException input 为空
     * @throws IllegalStateException    原始 segments 缺失（先跑产字幕）、解析或写入失败
     */
    public CleanedSubtitle clean(MediaInput input, SubtitleKeywords keywords) {
        return clean(input, keywords, ProgressListener.noop());
    }

    /**
     * 清洗一份字幕。
     *
     * @param input    媒体输入；不可为空
     * @param keywords 用户手填的关键词（专有名词正确写法）；不可为 {@code null}
     * @param listener 进度监听；不可为 {@code null}——不关心进度用两参重载
     * @return 清洗产物（路径 + 语种 + 句数 + 修正/重试统计）
     * @throws IllegalArgumentException input 或 listener 为空
     * @throws IllegalStateException    原始 segments 缺失（先跑产字幕）、解析或写入失败
     */
    public CleanedSubtitle clean(MediaInput input, SubtitleKeywords keywords, ProgressListener listener) {
        return doClean(input, keywords, listener, false);
    }

    /**
     * <b>按当前关键词重新生成一遍清洗产物</b>：与 {@link #clean} 只差两条规则。
     *
     * <ol>
     *   <li><b>跳过 {@code -clean} 检查点</b>——关键词没变也照样重跑。用户点「重新生成」就是想要一份
     *       新的（模型温度不是零，同样的输入也值得再试一次）。</li>
     *   <li><b>摘要失败即整体失败</b>，抛 {@link IllegalStateException} → 调用方广播 FAILED，<b>什么都不删</b>。
     *       首次跑宁可没上下文也要出字幕，那是 {@link #clean} 的取舍；这里用户已明确要求「用这批关键词
     *       重生成」，拿空上下文出一份没纠错的字幕等于没执行他的要求，而且他看不出来。</li>
     * </ol>
     *
     * <p><b>删除顺序是「先成功、后删」</b>：清洗产物写成功之后才删 {@code -translate-*} 与 {@code terms-*}。
     * 反过来先删，重跑失败时用户会同时失去字幕和译文，从「能用」掉到「什么都没有」。
     *
     * @param input    媒体输入；不可为空
     * @param keywords 本次要用的关键词（调用方已落库）；不可为 {@code null}
     * @param listener 进度监听；不可为 {@code null}
     * @return 清洗产物（路径 + 语种 + 句数 + 修正/重试统计）
     * @throws IllegalArgumentException input 或 listener 为空
     * @throws IllegalStateException    原始 segments 缺失、摘要降级为空上下文、解析或写入失败
     */
    public CleanedSubtitle regenerate(MediaInput input, SubtitleKeywords keywords, ProgressListener listener) {
        return doClean(input, keywords, listener, true);
    }

    /**
     * 清洗一条路径：两条入口（首次跑 / 重新生成）在这里汇合，只有 {@code regenerate} 这一个布尔不同。
     *
     * @param input      媒体输入；不可为空
     * @param keywords   用户手填的关键词；不可为 {@code null}
     * @param listener   进度监听；不可为 {@code null}
     * @param regenerate {@code true} = 跳过 {@code -clean} 检查点、摘要失败即整体失败、成功后清理翻译与术语产物
     * @return 清洗产物
     */
    private CleanedSubtitle doClean(MediaInput input, SubtitleKeywords keywords,
                                    ProgressListener listener, boolean regenerate) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        if (listener == null) {
            throw new IllegalArgumentException("进度监听不能为 null：请传一个 ProgressListener，"
                    + "或改用不带 listener 的 clean(input, keywords) 两参重载");
        }
        if (keywords == null) {
            throw new IllegalArgumentException("关键词不能为 null：请传 new SubtitleKeywords(List.of()) 表示不纠这一类");
        }
        SubtitleRoute route = asrProvider.route();
        WorkspaceKey workspaceKey = workspaceKeyGenerator.parseMediaToWorkspaceKey(input);
        Path workDir = workspaceDirectoryManager.ensureWorkspaceDirectory(input.dirLabel(), workspaceKey);
        Path rawSeg = workDir.resolve(SubtitleArtifactSpec.SEGMENTS_RAW.fileName(route, null));
        Path cleanSeg = workDir.resolve(SubtitleArtifactSpec.SEGMENTS_CLEAN.fileName(route, null));
        Path cleanSrt = workDir.resolve(SubtitleArtifactSpec.SRT_CLEAN.fileName(route, null));
        SubtitleTrack existing = readCheckpoint(cleanSeg);
        String fingerprint = keywords.computeFingerprint();

        // 「这份产物就是当前关键词生成的」——既管断点复用，也管摘要缓存能不能用
        boolean current = existing != null
                && CheckpointPolicy.isCurrent(ArtifactFiles.sizeOrZero(cleanSeg), existing.keywordsFingerprint(), fingerprint);

        String cleanSubtitlesStageName = SubtitleStageName.CLEAN_SUBTITLES.getStageName();
        if (current && !regenerate) {
            log.info("字幕清洗断点复用跳过: clean={}", cleanSeg.getFileName());
            // 断点复用不重跑，本趟统计一律为 0（含术语指标），语义与 correctedCount 一致

            emit(listener, cleanSubtitlesStageName, SubtitleStageName.CLEAN_SUBTITLES.end(), "已有清洗产物，跳过清洗",
                    StageProgress.State.SKIPPED);
            return toCleaned(existing, cleanSeg, cleanSrt, 0, 0, 0, 0);
        }
        // 开始事件先于「原始字幕缺失」卫语句发出——失败任务的用户消息里能带上所在阶段
        emit(listener, cleanSubtitlesStageName, SubtitleStageName.CLEAN_SUBTITLES.start(),
                regenerate ? "开始重新生成字幕" : "开始清洗", StageProgress.State.RUNNING);

        if (!ArtifactFiles.isNonEmpty(rawSeg)) {
            throw new IllegalStateException("字幕尚未获取：请先调用 POST /api/subtitles 产出原始字幕，再清洗（缺 "
                    + rawSeg.getFileName() + "）");
        }
        SubtitleTrack raw = readRaw(rawSeg);
        List<TranscriptionSegment> segments = raw.segments();
        String radioContent = segments.stream().map(TranscriptionSegment::text).collect(Collectors.joining("\n"));
        Path ctxFile = workDir.resolve(SubtitleArtifactSpec.CONTEXT.fileName(route, null));
        // 缓存只在「旧产物就是当前这份关键词生成的」时可用；否则那份上下文可能是别的关键词集下产的
        SubtitleContext cached = current
                ? ArtifactFiles.readJsonOrNull(ctxFile, objectMapper, SubtitleContext.class)
                : null;
        CheckpointPolicy.ContextResolution contextResolution = CheckpointPolicy.resolveContext(cached,
                () -> contextSummarizer.summarize(radioContent, keywords));
        SubtitleContext subtitleContext = contextResolution.context();
        switch (contextResolution.source()) {
            // 摘要成功才落盘：空上下文落盘会被下次当成缓存命中，摘要永久丢失且不报错
            case FRESH_SUMMARY -> {
                ArtifactFiles.writeJsonAtomically(subtitleContext, objectMapper, ctxFile);
                log.debug("字幕上下文摘要已落盘: file={}", ctxFile.getFileName());
            }
            case DEGRADED_EMPTY -> {
                if (regenerate) {
                    // 重新生成的语义不允许空上下文（见方法注释第 2 条）
                    throw new IllegalStateException("字幕摘要失败，无法按当前关键词重新生成："
                            + "请检查摘要所用的模型配置与网络后重试；现有字幕与译文都未改动");
                }
                log.debug("字幕摘要失败，降级为空上下文（不落盘）");
            }
            case CACHE_HIT -> log.debug("字幕上下文缓存命中: file={}", ctxFile.getFileName());
        }

        // 切分
        List<TranscriptionSegment> split = new SubtitleSegmentSplitter(props.maxSegmentMs()).split(segments);

        // 清洗（分批 + 每批带主题/术语/后窗/跨批历史）；批级进度 cleanser 报自己的 0..100，换算进 clean 区间再往下传
        CleanseResult result = subtitleCleanser.clean(split, subtitleContext, keywords,
                ProgressMapper.remap(listener, SubtitleStageName.CLEAN_SUBTITLES.start(), SubtitleStageName.CLEAN_SUBTITLES.end()));

        SubtitleTrack cleaned = new SubtitleTrack(route, raw.language(), result.segments(), fingerprint);
        writeArtifacts(cleanSeg, cleanSrt, cleaned);   // 先 JSON（检查点）再 SRT（派生），都原子替换
        emit(listener, cleanSubtitlesStageName, SubtitleStageName.CLEAN_SUBTITLES.end(), "清洗完成", StageProgress.State.SUCCEEDED);
        log.info("字幕清洗完成: route={}, segments={}, corrected={}, retry={}, termHits={}, termApplied={}",
                route.name(), result.segments().size(), result.correctedCount(), result.retrySteps(),
                result.termHits(), result.termApplied());
        if (regenerate) {
            // 先成功、后删：走到这里说明新的 -clean 已经原子替换落盘了
            deleteTranslatedArtifacts(workDir);
        }
        return toCleaned(cleaned, cleanSeg, cleanSrt, result.correctedCount(), result.retrySteps(),
                result.termHits(), result.termApplied());
    }

    /**
     * 删掉翻译与术语查证产物：新的字幕出来之后，它们全都对不上当前内容了。
     *
     * <p>{@code terms-*} 一起删的理由：它是拿 {@code context.terms} 联网查出来的，重跑后术语可能变
     * （关键词进了摘要器），留着就是一份对不上当前术语表的旧译名——错配的译名会被翻译照用，属于静默出错。
     * 代价是上次查证的搜索费白花，下次触发翻译时重查一遍。
     *
     * <p>删不存在的文件就是「已经删过了」：{@code deleteIfExists} 不判存在、不额外处理。
     * 删除本身失败只记 WARN：字幕已经生成好了，为几个派生产物把整趟重新生成判失败不值当。
     */
    private void deleteTranslatedArtifacts(Path workDir) {
        int deleted = 0;
        try (Stream<Path> entries = Files.list(workDir)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (SubtitleArtifactSpec.SEGMENTS_TRANSLATED.matches(name)
                        || SubtitleArtifactSpec.SRT_TRANSLATED.matches(name)
                        || SubtitleArtifactSpec.TERMS.matches(name)) {
                    Files.deleteIfExists(entry);
                    deleted++;
                }
            }
        } catch (IOException e) {
            // 不打目录名：它内嵌 URL / 原始文件名（用户资源，脱敏红线）
            log.warn("重新生成成功后清理翻译与术语产物失败，产物可能仍留在工作区: reason={}", e.getMessage());
            return;
        }
        if (deleted > 0) {
            log.info("重新生成成功，已清理 {} 份翻译 / 术语产物（译文对不上新字幕了）", deleted);
        }
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
     * 发一个阶段事件（progress 是任务级值：调用处直接传 {@code CLEAN_SUBTITLES} 的区间端点；
     * 批级进度不走这里，由 cleanser 经 {@code ProgressMapper} 换算后直接回调 listener）。
     */
    private static void emit(ProgressListener listener, String stage, int progress, String message,
                             StageProgress.State state) {
        listener.onProgress(new StageProgress(stage, progress, message, state));
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