package com.vidsage.service;

import com.vidsage.config.SubtitleTranslateProperties;
import com.vidsage.model.asr.TranscriptionSegment;
import com.vidsage.model.media.MediaInput;
import com.vidsage.model.subtitle.SubtitleContext;
import com.vidsage.model.subtitle.SubtitleRoute;
import com.vidsage.model.subtitle.SubtitleTrack;
import com.vidsage.model.subtitle.TranslatedSegment;
import com.vidsage.model.subtitle.TranslatedSubtitle;
import com.vidsage.model.subtitle.TranslatedTrack;
import com.vidsage.provider.asr.ASRProvider;
import com.vidsage.provider.llm.SubtitleContextSummarizer;
import com.vidsage.provider.llm.SubtitleTranslator;
import com.vidsage.utils.fs.ArtifactFiles;
import com.vidsage.utils.subtitle.SrtRenderer;
import com.vidsage.utils.subtitle.SubtitleArtifactNames;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 字幕翻译编排：把「清洗后的 -clean 字幕」变成「双语 -translate 产物」。
 *
 * <p>管线形状：读 -clean segments → 取或摘要跨批上下文（{@link SubtitleContextSummarizer}）→
 * 判定源语种（源语种等于目标语种则跳过、不调 LLM）→ {@link SubtitleTranslator} 逐批翻译 →
 * 写 {@code segments-<route>-translate-<target>.json} + {@code subtitles-<route>-translate-<target>.srt}。
 *
 * <p><b>断点复用判据</b>：{@code -translate-<target>.json} 存在且非空即跳过翻译，直接反序列化返回。
 * 为什么拿 JSON 当检查点而不是 SRT：空转写产出 0 字节 SRT，拿 SRT 当检查点会让空产物永远判「未完成」
 * （同 {@link SubtitleCleanupService} / {@link SubtitleService}）。
 *
 * <p><b>只读 -clean 产物</b>：输入是 {@code segments-<route>-clean.json}（只读，必须已存在），写
 * {@code -translate}。<b>不静默回退到原始 segments</b>：术语替换的成果只在 -clean 里，回退会让它白做，
 * 而且是静默的。
 */
@Slf4j
@Service
public class SubtitleTranslationService {

    private final WorkspaceService workspaceService;
    private final ASRProvider asrProvider;
    private final ObjectMapper objectMapper;
    private final SubtitleTranslateProperties props;

    private final SubtitleContextSummarizer contextSummarizer;

    private final SubtitleTranslator subtitleTranslator;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param workspaceService   工作目录解析
     * @param asrProvider        当前装配的 ASR provider（取路线与产物命名指纹）
     * @param objectMapper       Jackson 3 序列化器（Boot 4 只有 tools.jackson 这份有 bean）
     * @param props              字幕翻译调参（目标语种 / 批次 / 重试 / 跨批历史上限）
     * @param contextSummarizer  内容总结器（摘要出主题 / 源语种 / 术语，供跨批上下文与跳过判定）
     * @param subtitleTranslator 字幕翻译器（持有翻译专用的 ChatClient）
     */
    public SubtitleTranslationService(WorkspaceService workspaceService,
                                      ASRProvider asrProvider,
                                      ObjectMapper objectMapper,
                                      SubtitleTranslateProperties props,
                                      SubtitleContextSummarizer contextSummarizer,
                                      SubtitleTranslator subtitleTranslator) {
        this.workspaceService = workspaceService;
        this.asrProvider = asrProvider;
        this.objectMapper = objectMapper;
        this.props = props;
        this.contextSummarizer = contextSummarizer;
        this.subtitleTranslator = subtitleTranslator;
    }

    /**
     * 翻译一份已清洗的字幕，产出双语 SRT 与翻译检查点。
     *
     * @param input 媒体输入（与产字幕、清洗同源，用于定位工作目录）；不可为空
     * @return 翻译产物（双语 SRT + segments 路径 + 源/目标语种 + 是否跳过 + 统计）
     * @throws IllegalArgumentException input 为空
     * @throws IllegalStateException    -clean 字幕缺失（先跑清洗）、解析或写入失败；目标语种为空
     */
    public TranslatedSubtitle translate(MediaInput input) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        // 目标语种是配置值、边界上即可判定，故卫语句排在最前（早于 workspaceFor 的建目录副作用）
        String targetLanguage = resolveTargetLanguage(props);
        SubtitleRoute route = asrProvider.route();
        Path workDir = workspaceService.workspaceFor(input);
        Path cleanSeg = workDir.resolve(SubtitleArtifactNames.cleanSegmentsFileName(route));
        Path translateSeg = workDir.resolve(SubtitleArtifactNames.translateSegmentsFileName(route, targetLanguage));
        Path translateSrt = workDir.resolve(SubtitleArtifactNames.translateSrtFileName(route, targetLanguage));

        // 断点：检查点已在且非空 → 反序列化直接返回，本趟统计一律归零（含 untranslatedCount）
        TranslatedTrack existing = ArtifactFiles.readJsonOrNull(translateSeg, objectMapper, TranslatedTrack.class);
        if (existing != null) {
            log.info("字幕翻译断点复用跳过: translate={}", translateSeg.getFileName());
            return toTranslated(existing, translateSeg, translateSrt, 0, 0);
        }

        // -clean 缺失：报「缺什么、怎么补」。抛 ISE → ApiExceptionHandler 映射为 500。
        // 语义上更贴 409/400（调用顺序错，非服务端故障），但本轮与清洗侧同一取舍（见 SubtitleCleanupService）：
        // 它是「服务端状态缺失」，可归入 error-handling 的「环境不对」；改成 4xx 超本任务「只新增」范围。
        if (!ArtifactFiles.isNonEmpty(cleanSeg)) {
            throw new IllegalStateException("字幕尚未清洗：请先调用 POST /api/subtitles/clean 产出 -clean 字幕，再翻译（缺 "
                    + cleanSeg.getFileName() + "）");
        }
        SubtitleTrack clean = readClean(cleanSeg);
        List<TranscriptionSegment> segments = clean.segments();

        // 上下文：先读缓存，没有才摘要。断点分支留在本层（不在 SubtitleContextSummarizer 里）——与 -clean
        // 断点判据同处，见 design §3.2。翻译依赖 -clean，所以清洗必定先跑并落下上下文，这里通常命中缓存。
        Path ctxFile = workDir.resolve(SubtitleArtifactNames.contextFileName(route));
        SubtitleContext context = ArtifactFiles.readJsonOrNull(ctxFile, objectMapper, SubtitleContext.class);
        if (context == null) {
            String content = segments.stream().map(TranscriptionSegment::text).collect(Collectors.joining("\n"));
            context = contextSummarizer.summarize(content).orElse(null);
            if (context != null) {
                // 摘要成功才落盘：降级出的空上下文绝不能写文件，否则下次会命中这份空缓存，摘要永久丢失且不报错
                // （design 决策 5）。
                ArtifactFiles.writeJsonAtomically(context, objectMapper, ctxFile);
                log.debug("字幕上下文摘要已落盘: file={}", ctxFile.getFileName());
            } else {
                context = SubtitleContext.empty();   // 降级，且不写文件
                log.debug("字幕摘要失败，降级为空上下文（不落盘）");
            }
        } else {
            log.debug("字幕上下文缓存命中: file={}", ctxFile.getFileName());
        }

        // 源语种：优先摘要推断（主路径），为空回退 ASR 的 language（实测多为 null，兜底），两者皆空则不跳过
        String sourceLanguage = resolveSourceLanguage(context, clean.language());

        TranslatedTrack track;
        int retrySteps = 0;
        int untranslated = 0;
        if (isSameMainLanguage(sourceLanguage, targetLanguage)) {
            // 源语种等于目标语种：不调 LLM，每条译文 = 原文（双语渲染器会把相同的两行并成一行）
            track = new TranslatedTrack(route, sourceLanguage, targetLanguage, true, skipAll(segments));
            log.info("源语种与目标语种相同，跳过翻译: source={}, targetLanguage={}, segments={}",
                    sourceLanguage, targetLanguage, segments.size());
        } else {
            SubtitleTranslator.TranslateResult result = subtitleTranslator.translate(segments, context, targetLanguage);
            track = new TranslatedTrack(route, sourceLanguage, targetLanguage, false, result.segments());
            retrySteps = result.retrySteps();
            untranslated = result.untranslatedCount();
        }

        writeArtifacts(translateSeg, translateSrt, track);   // 先 JSON（检查点）再 SRT（派生），都原子替换
        log.debug("字幕翻译完成: route={}, sourceLanguage={}, targetLanguage={}, skipped={}, segments={}, "
                        + "retry={}, untranslated={}",
                route.name(), sourceLanguage, targetLanguage, track.skipped(), track.segments().size(),
                retrySteps, untranslated);
        return toTranslated(track, translateSeg, translateSrt, retrySteps, untranslated);
    }

    /**
     * 解析目标语种：取配置值，空白抛 ISE，否则返回归一化（去空白 + 转小写）后的值。
     *
     * <p><b>为什么必须在 service 开头做</b>：产物名
     * （{@link SubtitleArtifactNames#translateSegmentsFileName}）里也有一道同样的守卫，但它排在
     * {@code workspaceService.workspaceFor} 之后——那只会在<b>建出工作目录之后</b>才报错，属「卫语句晚于 I/O」：
     * 配置错却先产生副作用。{@code error-handling.md} 的硬规则要求边界上可判定的配置错误零 I/O 返回
     * （与 {@code POST /api/media/audio} 先取整遍媒体再拒非法参数同一类问题）。产物名那道守卫保留作
     * <b>防御性兜底</b>，正常路径已被这里挡住。
     *
     * <p>包级可见 static：测试可直接断言（参照 {@code WorkspaceService.normalize}），不必落盘。
     *
     * @param props 字幕翻译配置
     * @return 归一化后的目标语种（trim + 小写），与产物名的归一化口径一致
     * @throws IllegalStateException 语种为 {@code null} 或空白（该值来自配置，属「环境不对」而非调用方传参不合法）
     */
    static String resolveTargetLanguage(SubtitleTranslateProperties props) {
        String target = props.targetLanguage();
        if (!StringUtils.hasText(target)) {
            throw new IllegalStateException(
                    "目标语种不能为空：请设置 vidsage.subtitle.translate.target-language"
                            + "（BCP-47 短码，如 zh / en）");
        }
        return target.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 判定跳过时采用的源语种：摘要推断的优先（主路径），为空回退 ASR 给的 language（兜底），
     * 两者皆空则返回空串（调用方据此<b>不跳过</b>）。
     *
     * <p>包级可见 static：测试可直接断言优先级，不必落盘。
     *
     * @param context       字幕上下文（可能为空上下文对象）
     * @param trackLanguage ASR 识别出的语种，可能为 {@code null} 或空白
     * @return 源语种；两者皆空时为空串
     */
    static String resolveSourceLanguage(SubtitleContext context, String trackLanguage) {
        if (context != null && StringUtils.hasText(context.sourceLanguage())) {
            return context.sourceLanguage().trim();
        }
        return StringUtils.hasText(trackLanguage) ? trackLanguage.trim() : "";
    }

    /**
     * 两份语种标签是不是同一门语言：<b>只比连字符前的主语言子标签</b>，大小写不敏感。
     *
     * <p><b>为什么不直接 {@code equals}</b>：LLM 可能返回 {@code zh-CN} 而不是 {@code zh}，直接比对会失配
     * → 本该跳过的中文视频被送去翻译一遍，<b>中文译成中文</b>，白烧一轮 LLM 还可能改坏原文；而这个失败
     * 是静默的（不报错、日志里也看不出异常）。{@code zh-CN} / {@code zh-Hant} 对「要不要跳过翻译」这个
     * 判断而言都还是中文，跳过是对的。
     *
     * <p><b>任一方为空即为 {@code false}</b>：两边都取不到语种时，主语言子标签都是空串，
     * 直接比会得出 {@code "" .equals("")} = {@code true}，把「两个都不知道是什么语言」判成同一门语言。
     * 而「语种未知则不跳过、照译」是本任务的既定取舍（宁可多跑一趟，不可静默漏译），
     * 故这里把「未知」显式排除，让这个方法单独拿去用也是对的。
     * （当前调用点走不到这个组合——{@code target} 已由 {@link #resolveTargetLanguage} 保证非空——
     * 这道守卫是给它作为一个独立谓词用的场景兜底。）
     *
     * <p>包级可见 static：测试可直接断言，不必落盘。
     *
     * @param a 语种标签，可为 {@code null} 或空白
     * @param b 语种标签，可为 {@code null} 或空白
     * @return 主语言子标签相同且都非空时为 {@code true}；任一方取不到语种时为 {@code false}
     */
    static boolean isSameMainLanguage(String a, String b) {
        String left = mainLanguageSubtag(a);
        String right = mainLanguageSubtag(b);
        return !left.isEmpty() && !right.isEmpty() && left.equals(right);
    }

    /**
     * 取 BCP-47 标签的主语言子标签：去空白、转小写、截到第一个连字符之前。
     *
     * @param language 语种标签，可为 {@code null}
     * @return 主语言子标签；{@code null} 时为空串
     */
    private static String mainLanguageSubtag(String language) {
        if (language == null) {
            return "";
        }
        String trimmed = language.trim().toLowerCase(Locale.ROOT);
        int dash = trimmed.indexOf('-');
        return dash < 0 ? trimmed : trimmed.substring(0, dash);
    }

    /**
     * 跳过翻译时的逐条产物：译文照抄原文，时间戳原样带过。
     */
    private static List<TranslatedSegment> skipAll(List<TranscriptionSegment> segments) {
        return segments.stream()
                .map(s -> new TranslatedSegment(s.beginTimeMs(), s.endTimeMs(), s.text(), s.text()))
                .toList();
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
    private void writeArtifacts(Path translateSeg, Path translateSrt, TranslatedTrack track) {
        ArtifactFiles.writeJsonAtomically(track, objectMapper, translateSeg);
        ArtifactFiles.writeAtomically(
                SrtRenderer.toBilingualSrt(track.segments()).getBytes(StandardCharsets.UTF_8), translateSrt);
    }

    /**
     * 产物 → {@link TranslatedSubtitle}：带路径、源/目标语种、是否跳过、句数与文件字节数。
     */
    private static TranslatedSubtitle toTranslated(TranslatedTrack track, Path translateSeg, Path translateSrt,
                                                   int retrySteps, int untranslatedCount) {
        return new TranslatedSubtitle(translateSrt, translateSeg, track.route(), track.sourceLanguage(),
                track.targetLanguage(), track.skipped(), track.segments().size(),
                ArtifactFiles.sizeOrZero(translateSrt), retrySteps, untranslatedCount);
    }
}
