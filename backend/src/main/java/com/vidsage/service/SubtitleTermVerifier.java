package com.vidsage.service;

import com.vidsage.model.subtitle.SubtitleContext;
import com.vidsage.model.subtitle.TermLookupCommand;
import com.vidsage.model.subtitle.TermLookupResult;
import com.vidsage.provider.termlookup.TermLookupProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 字幕术语译名查证编排：把上下文里「还没查证过」的术语逐条交给 {@link TermLookupProvider}
 * 联网查证，把结论（官方/通用译名或弃权）写回术语，产出可直接落盘的新上下文。
 *
 * <p><b>触发是确定性的</b>（09-28 实证：模型不觉得自己在猜，「要不要查」不能交给任何一方的临场判断）：
 * 全量查证 {@code official == null} 的术语，不做「哪些值得查」的预过滤。本类只负责「哪些算未查证」
 * 与「什么条件才值得查」（源语言 ≠ 目标语言，翻译真的要发生；目标语种空白时无从查起），
 * 文件读写留在调用方——清洗与翻译两个接线点的落盘时机不同，见各自 service。
 *
 * <p><b>失败与弃权是两条路</b>：查证调用抛异常 = 该词保持 {@code official == null}（下次补跑）；
 * 查过但无有据译名 = {@code official} 置空串（弃权结论持久化，不再重搜烧钱）。两者靠 null/空串
 * 之别区分，见 {@link SubtitleContext.Term}。
 *
 * <p>查证 provider 缺席（yml 未配 {@code vidsage.term-lookup.provider}）= 整个查证关闭，
 * 原样返回，绝不挡清洗与翻译主流程——与「摘要失败降级空上下文」同一哲学。
 */
@Slf4j
@Service
public class SubtitleTermVerifier {

    private final ObjectProvider<TermLookupProvider> lookupProvider;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * <p>provider 用 {@link ObjectProvider} 而不是直接注入：查证是增益不是前提，
     * 配置里删掉 {@code vidsage.term-lookup.provider} 应当让应用照常起、只关查证，
     * 而不是缺 bean 起不来。
     */
    public SubtitleTermVerifier(ObjectProvider<TermLookupProvider> lookupProvider) {
        this.lookupProvider = lookupProvider;
    }

    /**
     * 查证一次上下文的全部未查证术语（内部先判触发条件，不满足就原样返回）。
     *
     * @param context        字幕上下文；可为 {@code null}（摘要降级时调用方拿到的就是 null）
     * @param targetLanguage 目标语种（BCP-47 短码）；空白时直接跳过——查证无从谈起，
     *                       目标语种缺失由翻译侧的既有卫语句另行报错
     * @return 查证结果：新上下文 + 逐类计数；{@code changed()} 为 {@code false} 表示上下文没动、
     *         调用方无需回写
     */
    public Result verifyIfNeeded(SubtitleContext context, String targetLanguage) {
        if (context == null || context.terms().isEmpty()) {
            return Result.unchanged(context);
        }
        String target = targetLanguage == null ? "" : targetLanguage.trim().toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(target)
                || SubtitleTranslationService.isSameMainLanguage(context.sourceLanguage(), target)) {
            log.debug("术语查证跳过（不满足触发条件）: terms={}, target={}", context.terms().size(), target);
            return Result.unchanged(context);
        }
        List<SubtitleContext.Term> terms = context.terms();
        long unverified = terms.stream().filter(t -> t.official() == null).count();
        if (unverified == 0) {
            log.debug("术语查证跳过（全部已查证）: terms={}", terms.size());
            return Result.unchanged(context);
        }
        TermLookupProvider provider = lookupProvider.getIfAvailable();
        if (provider == null) {
            log.debug("术语查证未启用（vidsage.term-lookup.provider 未配置），跳过: terms={}", terms.size());
            return Result.unchanged(context);
        }

        log.info("术语译名查证开始: terms={}, 未查证={}, target={}", terms.size(), unverified, target);
        long start = System.currentTimeMillis();
        List<SubtitleContext.Term> out = new ArrayList<>(terms.size());
        int official = 0;
        int common = 0;
        int none = 0;
        int failed = 0;
        for (SubtitleContext.Term term : terms) {
            if (term.official() != null) {
                out.add(term);   // 已查证（含弃权空串）：不动，也不再花搜索费
                continue;
            }
            try {
                TermLookupResult result =
                        provider.lookup(new TermLookupCommand(term.fixed(), target, context.theme()));
                String name = result.officialName() == null ? "" : result.officialName().trim();
                String note;
                if (StringUtils.hasText(name)) {
                    note = (result.official() ? "官方译名" : "通用译名，非官方")
                            + "（来源：" + describeSource(result.sourceUrl()) + "）";
                    if (result.official()) {
                        official++;
                    } else {
                        common++;
                    }
                } else {
                    none++;
                    note = "联网查证未找到有据可依的目标语种译名，弃权";
                }
                out.add(new SubtitleContext.Term(term.asr(), term.fixed(), term.note(), name, note));
            } catch (RuntimeException e) {
                // 查证失败 ≠ 查不到：保持未查证（null），下次补跑；绝不因查证挡清洗/翻译主流程
                failed++;
                out.add(term);
                log.warn("术语查证失败，保持未查证待下次: term={}, reason={}", term.fixed(), e.getMessage());
            }
        }
        SubtitleContext updated = new SubtitleContext(context.theme(), context.sourceLanguage(), out);
        log.info("术语译名查证完成: 共={}, 官方={}, 通用={}, 无={}, 失败={}, cost={}ms",
                terms.size(), official, common, none, failed, System.currentTimeMillis() - start);
        return new Result(updated, terms.size(), official, common, none, failed);
    }

    /**
     * 来源页只记 host：搜索给的来源是公开网页，没有签名参数，但完整 URL 拼进 note 会把
     * 查证说明撑得很长，host 足够人工回查（logging-guidelines 脱敏精神的同款取舍）。
     */
    private static String describeSource(String sourceUrl) {
        if (!StringUtils.hasText(sourceUrl)) {
            return "未知来源";
        }
        try {
            String host = URI.create(sourceUrl.trim()).getHost();
            return StringUtils.hasText(host) ? host : "未知来源";
        } catch (RuntimeException e) {
            return "未知来源";
        }
    }

    /**
     * 一次查证的结论：新上下文与逐类计数。
     *
     * @param context      查证后的上下文；触发条件不满足时就是入参原样
     * @param lookedUp     本趟真发了查证调用的术语数（含失败）；0 = 上下文没动
     * @param officialCount 查到官方译名的条数
     * @param commonCount  查到通用译名的条数
     * @param noneCount    弃权（查过、无有据译名）的条数
     * @param failedCount  调用失败、保持未查证的条数
     */
    public record Result(SubtitleContext context, int lookedUp, int officialCount, int commonCount,
                         int noneCount, int failedCount) {

        /**
         * @return {@code true} 表示上下文有改动、调用方需要回写
         */
        public boolean changed() {
            return lookedUp > 0;
        }

        static Result unchanged(SubtitleContext context) {
            return new Result(context, 0, 0, 0, 0, 0);
        }
    }
}
