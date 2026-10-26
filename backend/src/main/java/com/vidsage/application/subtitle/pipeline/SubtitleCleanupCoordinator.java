package com.vidsage.application.subtitle.pipeline;

import com.vidsage.config.SubtitleCleanupProperties;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.model.cleanup.CleanMode;
import com.vidsage.domain.subtitle.model.cleanup.CleanedSubtitle;
import com.vidsage.domain.subtitle.model.cleanup.CleanseResult;
import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.subtitle.model.common.SubtitleArtifactSpec;
import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;
import com.vidsage.domain.subtitle.policy.CheckpointPolicy;
import com.vidsage.domain.subtitle.port.SubtitleCleanser;
import com.vidsage.domain.subtitle.port.SubtitleContextSummarizer;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import com.vidsage.shared.fs.ArtifactFileUtils;
import com.vidsage.shared.progress.ProgressMapper;
import com.vidsage.shared.progress.StageEvents;
import com.vidsage.shared.subtitle.SrtRenderer;
import com.vidsage.shared.subtitle.SubtitleSegmentSplitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 字幕清洗编排：把产字幕阶段产出的原始 segments，变成「切分 + LLM 纠错后的 -clean 产物」。
 *
 * <p><b>复用判据与真跑分成两个方法</b>：{@link #reuseIfAvailable} 先回答「能不能复用」——
 * 产物表说这一版完好（调用方算好布尔值传进来），且盘上 {@code -clean} 在、字节数 &gt; 0、它记着的
 * 指纹与当前关键词一致；命中就交出那份产出，<b>不碰 {@code context.json}</b>（那份摘要的兑现点
 * 挪到下次真清洗）。{@link #clean} 只管真跑：切分 → 摘要（或读缓存）→ 纠错 → 写盘。
 *
 * <p><b>只看文件会把已经作废的清洗当成有效检查点</b>——内容变更级联只动数据库，不会去删干净盘上
 * 文件。所以产物表那一半由调用方回答，说不能复用就完全忽略盘上检查点，连上下文缓存都不读
 * （那份摘要也是旧内容算出来的）。
 *
 * <p><b>摘不出来时落盘带降级标记的空上下文</b>：这份标记表示「这次试过了」，读回时绝不算缓存命中，
 * 下次真清洗时仍会重试摘要。
 *
 * <p>检查点语义（为什么是 JSON 而不是 SRT）单源在 {@link CheckpointPolicy}；但「JSON 在不在、读不读得动」
 * 这一步走 {@code ArtifactFileUtils.readJsonOrNull}，本类不另判。
 * 只读产字幕阶段的原始产物（{@code original-segments.json}，必须已存在），只写 {@code clean-*}。
 */
@Slf4j
@Component
public class SubtitleCleanupCoordinator {

    private final ObjectMapper objectMapper;
    private final SubtitleCleanupProperties props;

    private final SubtitleContextSummarizer contextSummarizer;

    private final SubtitleCleanser subtitleCleanser;

    /**
     * @param objectMapper      Jackson 3 序列化器（Boot 4 只有 tools.jackson 这份有 bean）
     * @param props             字幕清洗调参（切分预算 / 批次 / 重试 / 相似度阈值）
     * @param contextSummarizer 内容总结器（摘要出主题与术语，供跨批上下文用）
     * @param subtitleCleanser  字幕清洗器（持有清洗专用的 ChatClient）
     */
    public SubtitleCleanupCoordinator(ObjectMapper objectMapper,
                                      SubtitleCleanupProperties props,
                                      SubtitleContextSummarizer contextSummarizer,
                                      SubtitleCleanser subtitleCleanser) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.contextSummarizer = contextSummarizer;
        this.subtitleCleanser = subtitleCleanser;
    }

    /**
     * 能不能复用清洗产物：产物表说完好（{@code artifactIntact}），且盘上 {@code -clean} 检查点解析得出来、
     * 文件里记的关键词指纹与当前一致。命中时交出那份产出，并广播一条 SKIPPED。
     * 命中时<b>不碰 {@code context.json}</b>（那份摘要的兑现点挪到下次真清洗）。
     *
     * @param workDir        这条输入的工作区目录（调用方已定位好）；不可为空
     * @param keywords       用户手填的关键词；不可为 {@code null}
     * @param artifactIntact 产物表里有没有完好的清洗产物；{@code false} 时完全不读文件
     * @param listener       阶段进度监听；不可为 {@code null}
     * @return 命中的那份清洗产物；不能复用时为空
     * @throws IllegalArgumentException 工作区目录 / 关键词 / 监听为空
     */
    public Optional<CleanedSubtitle> reuseIfAvailable(Path workDir, SubtitleKeywords keywords,
                                                      boolean artifactIntact, ProgressListener listener) {
        if (workDir == null) {
            throw new IllegalArgumentException("工作区目录不能为空：调用方应先定位好这条输入的工作区");
        }
        if (keywords == null) {
            throw new IllegalArgumentException("关键词不能为 null：请传 new SubtitleKeywords(List.of()) 表示不纠这一类");
        }
        if (listener == null) {
            throw new IllegalArgumentException("进度监听不能为 null：请传一个 ProgressListener");
        }
        if (!artifactIntact) {
            return Optional.empty();
        }
        Path cleanSeg = workDir.resolve(SubtitleArtifactSpec.CLEAN_SEGMENTS.getFileName(null));
        SubtitleTrack existing = ArtifactFileUtils.readJsonOrNull(cleanSeg, objectMapper,
                SubtitleTrack.class, "clean");
        boolean current = existing != null && CheckpointPolicy.isCurrent(
                ArtifactFileUtils.sizeOrZero(cleanSeg), existing.keywordsFingerprint(), keywords.computeFingerprint());
        if (!current) {
            return Optional.empty();
        }
        Path cleanSrt = workDir.resolve(SubtitleArtifactSpec.CLEAN_SRT.getFileName(null));
        log.info("字幕清洗断点复用跳过: clean={}", cleanSeg.getFileName());
        // 断点复用不重跑，本趟统计一律为 0（含术语指标），语义与 correctedCount 一致
        StageEvents.emit(listener, SubtitleStageName.CLEAN_SUBTITLES.getStageName(),
                SubtitleStageName.CLEAN_SUBTITLES.end(), "已有清洗产物，跳过清洗", StageProgress.State.SKIPPED);
        return Optional.of(toCleaned(existing, cleanSeg, cleanSrt, 0, 0, 0, 0));
    }

    /**
     * 清洗一份字幕，不报告进度。
     *
     * @param input              媒体输入；不可为空
     * @param workDir            这条输入的工作区目录（调用方已定位好）
     * @param keywords           用户手填的关键词；不可为 {@code null}
     * @param mayUseContextCache 产物表里有没有完好的清洗产物；没有就不能读盘上的上下文缓存
     * @return 清洗产物（路径 + 语种 + 句数 + 修正/重试统计）
     */
    public CleanedSubtitle clean(MediaInput input, Path workDir, SubtitleKeywords keywords,
                                 boolean mayUseContextCache) {
        return clean(input, workDir, keywords, ProgressListener.noop(), mayUseContextCache);
    }

    /**
     * 清洗一份字幕。
     *
     * <p>{@code mayUseContextCache=false} 时<b>完全忽略</b>盘上的 {@code -clean} 检查点：
     * 内容变更级联只把产物行标成作废，不会去删干净盘上文件，光看文件会把旧内容当成检查点命中。
     * 忽略检查点同时意味着不读它的上下文缓存——那份摘要也是旧内容算出来的。
     *
     * @param input              媒体输入；不可为空
     * @param workDir            这条输入的工作区目录（调用方已定位好）
     * @param keywords           用户手填的关键词（专有名词正确写法）；不可为 {@code null}
     * @param listener           进度监听；不可为 {@code null}——不关心进度用不带 listener 的重载
     * @param mayUseContextCache 产物表里有没有完好的清洗产物；没有就不能读盘上的上下文缓存
     * @return 清洗产物（路径 + 语种 + 句数 + 修正/重试统计）
     * @throws IllegalArgumentException input / listener / keywords 为空
     * @throws IllegalStateException    原始 segments 缺失（先跑产字幕）、解析或写入失败
     */
    public CleanedSubtitle clean(MediaInput input, Path workDir, SubtitleKeywords keywords,
                                 ProgressListener listener, boolean mayUseContextCache) {
        return doClean(input, workDir, keywords, listener, mayUseContextCache, CleanMode.FIRST_CLEAN);
    }

    /**
     * <b>按当前关键词重新生成一遍清洗产物</b>：与 {@link #clean} 只差一条规则——
     * <b>摘要失败即整体失败</b>，抛 {@link IllegalStateException} → 调用方广播 FAILED，<b>什么都不删</b>。
     * 首次跑宁可没上下文也要出字幕，那是 {@link #clean} 的取舍；这里用户已明确要求「用这批关键词
     * 重生成」，拿空上下文出一份没纠错的字幕等于没执行他的要求，而且他看不出来。
     *
     * <p>本方法也是真重跑、不复用检查点：走到它的入口本来就意味着用户想要一份新的（模型温度不是零，
     * 同样的输入也值得再试一次）。关键词没变时仍可读盘上的上下文缓存。
     *
     * <p><b>本类只写文件，不管数据库</b>：新清洗产物让下游译文对不上了，那件事（作废、删文件）
     * 由落定产物那一步负责，本类不参与。
     *
     * @param input    媒体输入；不可为空
     * @param workDir  这条输入的工作区目录（调用方已定位好）
     * @param keywords 本次要用的关键词（调用方已落库）；不可为 {@code null}
     * @param listener 进度监听；不可为 {@code null}
     * @return 清洗产物（路径 + 语种 + 句数 + 修正/重试统计）
     * @throws IllegalArgumentException input 或 listener 为空
     * @throws IllegalStateException    原始 segments 缺失、摘要降级为空上下文、解析或写入失败
     */
    public CleanedSubtitle regenerate(MediaInput input, Path workDir, SubtitleKeywords keywords, ProgressListener listener) {
        return doClean(input, workDir, keywords, listener, true, CleanMode.RECLEAN);
    }

    /**
     * 清洗一条路径：两条入口（首次跑 / 重新生成）在这里汇合。检查点复用已搬进 {@link #reuseIfAvailable}，
     * 这里只做真跑。
     *
     * @param input                 媒体输入；不可为空
     * @param workDir               这条输入的工作区目录（调用方已定位好）
     * @param keywords              用户手填的关键词；不可为 {@code null}
     * @param listener              进度监听；不可为 {@code null}
     * @param mayUseContextCache    {@code false} = 不读盘上的 {@code -clean} 与它的上下文缓存
     * @param mode                  这次清洗的意图：决定开始事件的文案，以及摘要降级是否致命
     * @return 清洗产物
     */
    private CleanedSubtitle doClean(MediaInput input, Path workDir, SubtitleKeywords keywords,
                                    ProgressListener listener, boolean mayUseContextCache, CleanMode mode) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        if (listener == null) {
            throw new IllegalArgumentException("进度监听不能为 null：请传一个 ProgressListener，"
                    + "或改用不带 listener 的 clean(input, workDir, keywords, mayUseContextCache) 重载");
        }
        if (keywords == null) {
            throw new IllegalArgumentException("关键词不能为 null：请传 new SubtitleKeywords(List.of()) 表示不纠这一类");
        }
        Path originalSeg = workDir.resolve(SubtitleArtifactSpec.ORIGINAL_SEGMENTS.getFileName(null));
        Path cleanSeg = workDir.resolve(SubtitleArtifactSpec.CLEAN_SEGMENTS.getFileName(null));
        Path cleanSrt = workDir.resolve(SubtitleArtifactSpec.CLEAN_SRT.getFileName(null));
        Path ctxFile = workDir.resolve(SubtitleArtifactSpec.CONTEXT.getFileName(null));
        // 表说不能复用就完全别看文件：盘上的 -clean 与上下文都可能是上一次内容留下的
        SubtitleTrack existing = mayUseContextCache
                ? ArtifactFileUtils.readJsonOrNull(cleanSeg, objectMapper, SubtitleTrack.class, "clean")
                : null;
        String fingerprint = keywords.computeFingerprint();

        // 「这份产物就是当前关键词生成的」——只用来决定上下文缓存能不能用
        boolean current = existing != null
                && CheckpointPolicy.isCurrent(ArtifactFileUtils.sizeOrZero(cleanSeg), existing.keywordsFingerprint(), fingerprint);

        String cleanSubtitlesStageName = SubtitleStageName.CLEAN_SUBTITLES.getStageName();
        // 这里发的都是任务级进度（直接传 CLEAN_SUBTITLES 的区间端点）；批级进度不走这里，
        // 由 cleanser 经 ProgressMapper 换算后直接回调 listener
        // 开始事件先于「原始字幕缺失」卫语句发出——失败任务的用户消息里能带上所在阶段
        StageEvents.emit(listener, cleanSubtitlesStageName, SubtitleStageName.CLEAN_SUBTITLES.start(),
                mode.startMessage(), StageProgress.State.RUNNING);

        if (!ArtifactFileUtils.isNonEmpty(originalSeg)) {
            throw new IllegalStateException("字幕尚未获取：请先调用 POST /api/subtitles 产出原始字幕，再清洗（缺 "
                    + originalSeg.getFileName() + "）");
        }
        SubtitleTrack original = readOriginal(originalSeg);
        List<TranscriptionSegment> segments = original.segments();
        String radioContent = segments.stream().map(TranscriptionSegment::text).collect(Collectors.joining("\n"));
        // 缓存只在「旧产物就是当前这份关键词生成的」时可用；否则那份上下文可能是别的关键词集下产的
        SubtitleContext cached = mayUseContextCache && current
                ? ArtifactFileUtils.readJsonOrNull(ctxFile, objectMapper, SubtitleContext.class)
                : null;
        CheckpointPolicy.ContextResolution contextResolution = CheckpointPolicy.resolveContext(cached,
                () -> contextSummarizer.summarize(radioContent, keywords));
        SubtitleContext subtitleContext = contextResolution.context();
        switch (contextResolution.source()) {
            // 摘要成功与降级都落盘；降级那份带标记，下次读回会重新摘要
            case FRESH_SUMMARY -> {
                ArtifactFileUtils.writeJsonAtomically(subtitleContext, objectMapper, ctxFile);
                log.debug("字幕上下文摘要已落盘: file={}", ctxFile.getFileName());
            }
            case DEGRADED_EMPTY -> {
                if (mode.requireContextSummary()) {
                    // 重新生成的语义不允许空上下文（见 regenerate 的注释）
                    throw new IllegalStateException("字幕摘要失败，无法按当前关键词重新生成："
                            + "请检查摘要所用的模型配置与网络后重试；现有字幕与译文都未改动");
                }
                ArtifactFileUtils.writeJsonAtomically(subtitleContext, objectMapper, ctxFile);
                log.info("字幕摘要失败，已落盘降级标记（下次仍会重试摘要）: file={}", ctxFile.getFileName());
            }
            case CACHE_HIT -> log.debug("字幕上下文缓存命中: file={}", ctxFile.getFileName());
        }

        // 切分
        List<TranscriptionSegment> split = new SubtitleSegmentSplitter(props.maxSegmentMs()).split(segments);

        // 清洗（分批 + 每批带主题/术语/后窗/跨批历史）；批级进度 cleanser 报自己的 0..100，换算进 clean 区间再往下传
        CleanseResult result = subtitleCleanser.clean(split, subtitleContext, keywords,
                ProgressMapper.remap(listener, SubtitleStageName.CLEAN_SUBTITLES.start(), SubtitleStageName.CLEAN_SUBTITLES.end()));

        SubtitleTrack cleaned = new SubtitleTrack(
                resolveLanguage(original.language(), subtitleContext), result.segments(), fingerprint);
        writeArtifacts(cleanSeg, cleanSrt, cleaned);   // 先 JSON（检查点）再 SRT（派生），都原子替换
        StageEvents.emit(listener, cleanSubtitlesStageName, SubtitleStageName.CLEAN_SUBTITLES.end(), "清洗完成", StageProgress.State.SUCCEEDED);
        log.info("字幕清洗完成: segments={}, corrected={}, retry={}, termHits={}, termApplied={}",
                result.segments().size(), result.correctedCount(), result.retrySteps(),
                result.termHits(), result.termApplied());
        return toCleaned(cleaned, cleanSeg, cleanSrt, result.correctedCount(), result.retrySteps(),
                result.termHits(), result.termApplied());
    }

    /**
     * 定这份字幕的语种：ASR 给了就用 ASR 的，没给就用上下文摘要里那个。
     *
     * <p>云端 ASR 实测不返回语种（qwen-audio-3.1-asr-flash-filetrans 的结果 JSON 里根本没有
     * language 字段），光看它这一份永远是 null；而摘要那步本来就通读了整篇字幕文本、语种判断是它
     * 顺带得出的，拿它兜底比空着强——下游（前端锁源语种、后端校验）都只认这一份 {@code language}。
     *
     * <p>兜底值只收能对到支持白名单的写法（zh / zh-CN / ja …）。模型偶尔会写「中文」这种自由文本，
     * 原样塞进 {@code language} 只会让下游解析不动，不如留 null 表示「没认出来」。
     *
     * @param asrLanguage ASR 给的语种，可能为空
     * @param context     本次清洗用的上下文，理论非空
     * @return 可以落进 {@code SubtitleTrack#language} 的值；两条路都没有就返回 {@code null}
     */
    private static String resolveLanguage(String asrLanguage, SubtitleContext context) {
        if (StringUtils.hasText(asrLanguage)) {
            return asrLanguage;
        }
        if (context == null) {
            return null;
        }
        return SupportedLanguage.getFirstMatchLang(context.sourceLanguage())
                .map(SupportedLanguage::getCode)
                .orElse(null);
    }

    /**
     * 读原始 segments JSON；解析失败抛 ISE（原始若坏，无法清洗）。
     */
    private SubtitleTrack readOriginal(Path originalSeg) {
        try {
            return objectMapper.readValue(originalSeg.toFile(), SubtitleTrack.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("原始字幕 segments 解析失败：" + originalSeg.getFileName()
                    + "；原因：" + e.getMessage(), e);
        }
    }

    /**
     * 写两个 -clean 产物：先 segments JSON（检查点），再 SRT（由 track 渲染）。都原子替换。
     */
    private void writeArtifacts(Path cleanSeg, Path cleanSrt, SubtitleTrack track) {
        ArtifactFileUtils.writeJsonAtomically(track, objectMapper, cleanSeg);
        ArtifactFileUtils.writeAtomically(
                SrtRenderer.toSrt(track.segments()).getBytes(StandardCharsets.UTF_8), cleanSrt);
    }


    /**
     * 产物 → {@link CleanedSubtitle}：带路径、语种、句数与文件字节数。
     */
    private static CleanedSubtitle toCleaned(SubtitleTrack track, Path cleanSeg, Path cleanSrt,
                                             int correctedCount, int retrySteps,
                                             int termHits, int termApplied) {
        return new CleanedSubtitle(cleanSrt, cleanSeg, track.language(),
                track.segments().size(), ArtifactFileUtils.sizeOrZero(cleanSrt), correctedCount, retrySteps,
                termHits, termApplied);
    }
}
