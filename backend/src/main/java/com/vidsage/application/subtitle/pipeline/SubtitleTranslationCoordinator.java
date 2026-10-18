package com.vidsage.application.subtitle.pipeline;

import com.vidsage.config.SubtitleTranslateProperties;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.subtitle.model.common.SubtitleArtifactSpec;
import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.model.term.VerifiedTerm;
import com.vidsage.domain.subtitle.model.term.VerifiedTerms;
import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;
import com.vidsage.domain.subtitle.model.translation.TranslateResult;
import com.vidsage.domain.subtitle.model.translation.TranslatedSubtitle;
import com.vidsage.domain.subtitle.model.translation.TranslatedTrack;
import com.vidsage.domain.subtitle.policy.Bcp47LanguagePolicy;
import com.vidsage.domain.subtitle.policy.CheckpointPolicy;
import com.vidsage.domain.subtitle.policy.TermVerification;
import com.vidsage.domain.subtitle.port.SubtitleContextSummarizer;
import com.vidsage.domain.subtitle.port.SubtitleTranslator;
import com.vidsage.domain.subtitle.port.TermLookupProvider;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import com.vidsage.shared.fs.ArtifactFiles;
import com.vidsage.shared.progress.StageEvents;
import com.vidsage.shared.subtitle.SrtRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 字幕翻译编排：把清洗后的 -clean 字幕变成双语 -translate 产物；源语种与目标语种相同时不调 LLM，
 * 直接产出「译文 = 原文」的产物。
 *
 * <p><b>断点复用</b>：{@code translated-segments-<target>.json} 存在且非空即跳过翻译，直接反序列化返回。
 * 检查点语义（为什么是 JSON 而不是 SRT）单源在 {@link CheckpointPolicy}，本类不另判。
 */
@Slf4j
@Component
public class SubtitleTranslationCoordinator {

    /**
     * 翻译阶段的进度起点（0..100 区间自报口径）。
     */
    private static final int STAGE_BEGIN = 0;

    /**
     * 翻译阶段的进度终点（0..100 区间自报口径）。
     */
    private static final int STAGE_DONE = 100;

    private final ObjectMapper objectMapper;
    private final SubtitleContextSummarizer contextSummarizer;

    private final ObjectProvider<TermLookupProvider> lookupProvider;

    private final SubtitleTranslator subtitleTranslator;

    /**
     * @param objectMapper      Jackson 3 序列化器（Boot 4 只有 tools.jackson 这份有 bean）
     * @param contextSummarizer 内容总结器（摘要出主题 / 源语种 / 术语，供跨批上下文与跳过判定）
     * @param lookupProvider    术语译名查证 provider，用 {@link ObjectProvider} 而不是直接注入：
     *                          查证是增益不是前提，配置里删掉 {@code vidsage.term-lookup.provider}
     *                          应当让应用照常起、只关查证，而不是缺 bean 起不来
     * @param subtitleTranslator 字幕翻译器（持有翻译专用的 ChatClient）
     */
    public SubtitleTranslationCoordinator(ObjectMapper objectMapper,
                                          SubtitleContextSummarizer contextSummarizer,
                                          ObjectProvider<TermLookupProvider> lookupProvider,
                                          SubtitleTranslator subtitleTranslator) {
        this.objectMapper = objectMapper;
        this.contextSummarizer = contextSummarizer;
        this.lookupProvider = lookupProvider;
        this.subtitleTranslator = subtitleTranslator;
    }

    /**
     * 翻译一份已清洗的字幕（不报告进度）。
     *
     * @param input          媒体输入（与产字幕、清洗同源，用于定位工作目录）；不可为空
     * @param workDir        这条输入的工作区目录（调用方已定位好）
     * @param sourceLanguage 源语种
     * @param targetLanguage 翻译目标语种
     * @return 翻译产物（双语 SRT + segments 路径 + 源/目标语种 + 是否跳过 + 统计）
     * @throws IllegalArgumentException input 为空
     * @throws IllegalStateException    -clean 字幕缺失（先跑清洗）、解析或写入失败；目标语种为空
     */
    public TranslatedSubtitle translate(MediaInput input, Path workDir, SupportedLanguage sourceLanguage, SupportedLanguage targetLanguage) {
        return translate(input, workDir, sourceLanguage, targetLanguage, ProgressListener.noop());
    }

    /**
     * 翻译一份已清洗的字幕，产出双语 SRT 与翻译检查点。
     *
     * @param input          媒体输入（与产字幕、清洗同源，用于定位工作目录）；不可为空
     * @param workDir        这条输入的工作区目录（调用方已定位好）
     * @param sourceLanguage 源语种
     * @param targetLanguage 翻译目标语种
     * @param listener       进度监听；不可为 {@code null}——不关心进度用不带 listener 的重载
     * @return 翻译产物（双语 SRT + segments 路径 + 源/目标语种 + 是否跳过 + 统计）
     * @throws IllegalArgumentException input 或 listener 为空
     * @throws IllegalStateException    -clean 字幕缺失（先跑清洗）、解析或写入失败；目标语种为空
     */
    public TranslatedSubtitle translate(MediaInput input, Path workDir, SupportedLanguage sourceLanguage, SupportedLanguage targetLanguage, ProgressListener listener) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        if (listener == null) {
            throw new IllegalArgumentException("进度监听不能为 null：请传一个 ProgressListener，"
                    + "或改用不带 listener 的 translate(input, workDir, sourceLanguage, targetLanguage) 重载");
        }
        String sourceLangCode = sourceLanguage.getCode();
        String targetLangCode = targetLanguage.getCode();

        Path cleanSeg = workDir.resolve(SubtitleArtifactSpec.CLEAN_SEGMENTS.getFileName(null));
        Path translateSeg = workDir.resolve(SubtitleArtifactSpec.TRANSLATED_SEGMENTS.getFileName(targetLangCode));
        Path translateSrt = workDir.resolve(SubtitleArtifactSpec.TRANSLATED_SRT.getFileName(targetLangCode));
        Path termsFile = workDir.resolve(SubtitleArtifactSpec.TERMS.getFileName(targetLangCode));
        Path ctxFile = workDir.resolve(SubtitleArtifactSpec.CONTEXT.getFileName(null));

        // 判断是否已经翻译过了，已翻译过了跳过
        Optional<TranslatedSubtitle> isReused = reuseCheckpointIfPresent(translateSeg, translateSrt, listener);
        if (isReused.isPresent()) {
            return isReused.get();
        }

        StageEvents.emit(listener, SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), STAGE_BEGIN, "开始翻译", StageProgress.State.RUNNING);
        if (!ArtifactFiles.isNonEmpty(cleanSeg)) {
            throw new IllegalStateException("字幕尚未清洗：请先调用 POST /api/subtitles/clean 产出 -clean 字幕，再翻译（缺 "
                    + cleanSeg.getFileName() + "）");
        }

        List<TranscriptionSegment> segments = readClean(cleanSeg).segments();
        SubtitleContext context = resolveContext(ctxFile, segments);

        // 术语官方译名查证（触发条件与失败分流见 verifyTermsIfNeeded）
        verifyTermsIfNeeded(context, sourceLanguage, targetLanguage, termsFile);

        TranslatedTrack track;
        int retrySteps = 0;
        int untranslated = 0;
        boolean skippedTranslation = sourceLanguage.isSameLanguage(targetLanguage);
        if (skippedTranslation) {
            // 源语种等于目标语种：不调 LLM，每条译文 = 原文（双语渲染器会把相同的两行并成一行）
            track = new TranslatedTrack(sourceLangCode, targetLangCode, true, Bcp47LanguagePolicy.skipAll(segments));
            log.info("源语种与目标语种相同，跳过翻译: source={}, targetLanguage={}, segments={}",
                    sourceLanguage, targetLanguage, segments.size());
        } else {
            List<VerifiedTerm> officialTerms = readOfficialTerms(termsFile);
            TranslateResult result =
                    subtitleTranslator.translate(segments, context, targetLangCode, officialTerms, listener);
            track = new TranslatedTrack(sourceLangCode, targetLangCode, false, result.segments());
            retrySteps = result.retrySteps();
            untranslated = result.untranslatedCount();
        }

        writeTranslatedArtifacts(translateSeg, translateSrt, track);   // 先 JSON（检查点）再 SRT（派生），都原子替换
        StageEvents.emit(listener, SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), STAGE_DONE,
                skippedTranslation ? "源语种与目标语种相同，跳过翻译" : "翻译完成",
                skippedTranslation ? StageProgress.State.SKIPPED : StageProgress.State.SUCCEEDED);
        log.debug("字幕翻译完成: sourceLanguage={}, targetLanguage={}, skipped={}, segments={}, "
                        + "retry={}, untranslated={}",
                sourceLanguage.getLabel(), targetLanguage.getLabel(), track.skipped(), track.segments().size(),
                retrySteps, untranslated);
        return toTranslated(track, translateSeg, translateSrt, retrySteps, untranslated);
    }

    /**
     * 断点复用：这个语种的译文检查点已经在、非空、能解析，就直接反序列化返回，本轮统计一律归零
     * （含 untranslatedCount）。
     *
     * <p>「在不在、空不空、坏没坏」的判据单源在 {@link ArtifactFiles#readJsonOrNull} 与
     * {@link CheckpointPolicy}，本方法不另判。
     *
     * @param translateSeg 译文 segments 检查点
     * @param translateSrt 配套的双语 SRT（复用时一并带回，不重新渲染）
     * @param listener     进度监听
     * @return 复用到的产物；没有可用检查点时为空
     */
    private Optional<TranslatedSubtitle> reuseCheckpointIfPresent(Path translateSeg, Path translateSrt,
                                                                  ProgressListener listener) {
        TranslatedTrack existing = ArtifactFiles.readJsonOrNull(translateSeg, objectMapper, TranslatedTrack.class);
        if (existing == null) {
            return Optional.empty();
        }
        log.info("字幕翻译断点复用跳过: translate={}", translateSeg.getFileName());
        StageEvents.emit(listener, SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), STAGE_DONE, "已有翻译产物，跳过翻译",
                StageProgress.State.SKIPPED);
        return Optional.of(toTranslated(existing, translateSeg, translateSrt, 0, 0));
    }

    /**
     * 取本次翻译要用的上下文：先读缓存，没有才摘要。用哪份的裁定单源在 {@link CheckpointPolicy#resolveContext}。
     *
     * <p><b>翻译侧摘要不带关键词，所以不落盘</b>：关键词是清洗那次调用的入参，翻译拿不到。清洗侧落的
     * 那份是带关键词摘要出来的，空关键词这份写下去会把它盖掉；而清洗命中断点复用后不会再来覆盖，
     * 这份残次品就常驻了。代价是缓存缺失时每次翻译都要重摘要一遍，换的是「不污染」。
     *
     * @param ctxFile  上下文文件
     * @param segments 清洗后的字幕（摘要用的原文）
     * @return 本次要用的上下文（缓存那份 / 新摘要 / 降级出来的空上下文）
     */
    private SubtitleContext resolveContext(Path ctxFile, List<TranscriptionSegment> segments) {
        SubtitleContext cached = ArtifactFiles.readJsonOrNull(ctxFile, objectMapper, SubtitleContext.class);
        CheckpointPolicy.ContextResolution resolution = CheckpointPolicy.resolveContext(cached,
                // 摘要请求惰性求值：缓存命中时连内容拼接与摘要调用都不发生
                () -> contextSummarizer.summarize(segments.stream()
                        .map(TranscriptionSegment::text)
                        .collect(Collectors.joining("\n")), new SubtitleKeywords(List.of())));
        switch (resolution.source()) {
            case FRESH_SUMMARY -> log.info("字幕上下文缓存缺失，本次现摘要（翻译侧不带关键词，不落盘）: file={}",
                    ctxFile.getFileName());
            case DEGRADED_EMPTY -> log.debug("字幕摘要失败，降级为空上下文（不落盘）");
            case CACHE_HIT -> log.debug("字幕上下文缓存命中: file={}", ctxFile.getFileName());
        }
        return resolution.context();
    }

    /**
     * 术语官方译名查证：把上下文里的术语逐条交给 {@link TermLookupProvider} 联网查证，
     * 结论（官方 / 通用译名或弃权）落独立的 {@code terms-<target>.json}。文件 I/O 留在本层。
     *
     * <p><b>为什么挂在翻译流程</b>：查证只为译文服务，翻译不发生就不烧搜索费（目标语种默认 zh 的
     * 中文素材走不到查证）。它是翻译的前置增益、不是独立环节，故内联在本类而不另建 bean；
     * 任何失败形态都不挡翻译主流程。
     *
     * <p>触发条件（查不查）单源在 {@link TermVerification#decide}，「失败与弃权两条路」的记账与落盘
     * 规则单源在 {@link TermVerification#verify}——本类不另判。
     *
     * @param context        字幕上下文（术语表的来源；可为降级的空上下文——术语为空时无事可做）
     * @param sourceLanguage 源语种（是否与目标语种相同由 {@link SupportedLanguage#isSameLanguage} 判）
     * @param targetLanguage 目标语种（已在 translate() 入口保证非空）
     * @param termsFile      查证产物路径
     */
    private void verifyTermsIfNeeded(SubtitleContext context, SupportedLanguage sourceLanguage,
                                     SupportedLanguage targetLanguage, Path termsFile) {
        TermLookupProvider provider = lookupProvider.getIfAvailable();
        TermVerification.Decision decision = TermVerification.decide(
                sourceLanguage.isSameLanguage(targetLanguage),
                CheckpointPolicy.isCompleted(ArtifactFiles.sizeOrZero(termsFile)),
                provider != null,
                !context.terms().isEmpty());
        switch (decision) {
            case SKIP_SAME_LANGUAGE -> {
                return;   // 同语种不翻译，也就不需要目标语种译名
            }
            case SKIP_ALREADY_VERIFIED -> {
                log.debug("术语查证产物已存在，跳过查证（删文件即重查）: file={}", termsFile.getFileName());
                return;
            }
            case SKIP_LOOKUP_DISABLED -> {
                log.debug("术语查证未启用（vidsage.term-lookup.provider 未配置），跳过: target={}", targetLanguage.getLabel());
                return;
            }
            case SKIP_NO_TERMS -> {
                return;   // 无术语可查；也不落空产物——空产物没有信息量，徒增一个「已完成」假象
            }
            case VERIFY -> {
            }
        }

        // 触发是确定性的：全量逐条查证，不做「哪些值得查」的预过滤——模型不觉得自己在猜
        log.info("术语译名查证开始: terms={}, target={}", context.terms().size(), targetLanguage.getLabel());
        long start = System.currentTimeMillis();
        TermVerification.Outcome outcome = TermVerification.verify(context, targetLanguage, provider);
        outcome.failures().forEach(failure ->
                log.warn("术语查证失败，该词不进产物待下次补查: term={}, reason={}",
                        failure.term(), failure.reason()));
        if (!outcome.persistable()) {
            // 全部失败：不写盘——落一份空产物会把整批词固化成「已查证」，下次永远不会再补查
            log.warn("术语查证全部失败，不落产物（下次翻译时重查）: terms={}, failed={}",
                    context.terms().size(), outcome.failures().size());
            return;
        }
        ArtifactFiles.writeJsonAtomically(new VerifiedTerms(outcome.verified()), objectMapper, termsFile);
        log.info("术语译名查证完成: 共={}, 官方={}, 通用={}, 无={}, 失败={}, cost={}ms",
                context.terms().size(), outcome.official(), outcome.common(), outcome.none(),
                outcome.failures().size(), System.currentTimeMillis() - start);
    }


    /**
     * 读术语官方译名产物，交给翻译器渲染「按给定译名翻」的术语参考。
     *
     * <p>查证步骤（{@link #verifyTermsIfNeeded}）刚执行过「存在 = 已查证」的检查；文件缺失
     * （查证未启用 / 无术语 / 全部失败 / 同语种跳过）按空表处理——官方译名是增益不是前提，
     * 缺了翻译照常进行。
     */
    private List<VerifiedTerm> readOfficialTerms(Path termsFile) {
        VerifiedTerms verified = ArtifactFiles.readJsonOrNull(termsFile, objectMapper, VerifiedTerms.class);
        if (verified == null) {
            log.debug("术语官方译名产物缺失，翻译按无官方译名进行: file={}", termsFile.getFileName());
            return List.of();
        }
        return verified.terms();
    }

    /**
     * 读清洗后的 segments JSON；解析失败抛 ISE（清洗产物若坏，无法翻译）。
     */
    private SubtitleTrack readClean(Path cleanSeg) {
        try {
            return objectMapper.readValue(cleanSeg.toFile(), SubtitleTrack.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("清洗后字幕 segments 解析失败：" + cleanSeg.getFileName()
                    + "；原因：" + e.getMessage(), e);
        }
    }

    /**
     * 写两个 -translate 产物：先 segments JSON（检查点），再双语 SRT（由 track 渲染）。都原子替换。
     */
    private void writeTranslatedArtifacts(Path translateSeg, Path translateSrt, TranslatedTrack track) {
        ArtifactFiles.writeJsonAtomically(track, objectMapper, translateSeg);
        ArtifactFiles.writeAtomically(
                SrtRenderer.toBilingualSrt(track.segments()).getBytes(StandardCharsets.UTF_8), translateSrt);
    }

    /**
     * 产物 → {@link TranslatedSubtitle}：带路径、源/目标语种、是否跳过、句数与文件字节数。
     */
    private static TranslatedSubtitle toTranslated(TranslatedTrack track, Path translateSeg, Path translateSrt,
                                                   int retrySteps, int untranslatedCount) {
        return new TranslatedSubtitle(translateSrt, translateSeg, track.sourceLanguage(),
                track.targetLanguage(), track.skipped(), track.segments().size(),
                ArtifactFiles.sizeOrZero(translateSrt), retrySteps, untranslatedCount);
    }
}
