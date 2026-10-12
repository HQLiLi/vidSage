package com.vidsage.domain.subtitle.policy;

import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import com.vidsage.domain.subtitle.model.term.TermLookupCommand;
import com.vidsage.domain.subtitle.model.term.TermLookupResult;
import com.vidsage.domain.subtitle.model.term.VerifiedTerm;
import com.vidsage.domain.subtitle.port.TermLookupProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.springframework.util.StringUtils;

/**
 * 术语官方译名查证政策（纯函数领域服务）：查证的「查不查、怎么记、落不落」三件事的单源。
 *
 * <p><b>为什么挂在翻译流程而不挂清洗</b>：译名是给目标语种的译文用的，只有真的要翻译
 * （源语种 ≠ 目标语种）才值得烧搜索费——目标语种默认 zh 的中文素材根本走不到查证。
 *
 * <p>无状态、不做文件 I/O：查证经 {@link TermLookupProvider}（本包端口）逐条联网发起。
 */
public final class TermVerification {

    private TermVerification() {
    }

    /**
     * 查证触发判定：<b>四条同时成立才查</b>（不同语种 → 产物未查证 → provider 未装配 → 无术语），否则静默跳过。
     *
     * @param sameMainLanguage      源语种与目标语种是否同一门主语言（同语种不翻译，也就不需要目标语种译名）
     * @param termsArtifactCompleted terms 查证产物是否已存在且非空（删文件即重查，换目标语种文件名不同自然重查）
     * @param lookupEnabled         查证 provider 是否在容器里（yml 删配置 = 查证整体关闭，跳过不报错）
     * @param hasTerms              上下文里是否有术语可查（无术语时也不落空产物——空产物徒增一个「已完成」假象）
     * @return 触发判定结论；跳过结论附带原因，供调用方记 debug 日志
     */
    public static Decision decide(boolean sameMainLanguage, boolean termsArtifactCompleted,
                                  boolean lookupEnabled, boolean hasTerms) {
        if (sameMainLanguage) {
            return Decision.SKIP_SAME_LANGUAGE;
        }
        if (termsArtifactCompleted) {
            return Decision.SKIP_ALREADY_VERIFIED;
        }
        if (!lookupEnabled) {
            return Decision.SKIP_LOOKUP_DISABLED;
        }
        if (!hasTerms) {
            return Decision.SKIP_NO_TERMS;
        }
        return Decision.VERIFY;
    }

    /**
     * 查证触发判定结论；按 {@link #decide} 的顺序短路给出。
     */
    public enum Decision {

        /** 同主语言：不翻译，也就不需要目标语种译名。 */
        SKIP_SAME_LANGUAGE,

        /** terms 产物已完成（存在且非空 = 已查证，删文件即重查）。 */
        SKIP_ALREADY_VERIFIED,

        /** 查证 provider 未装配（配置缺席 = 查证整体关闭，增益缺席不是故障）。 */
        SKIP_LOOKUP_DISABLED,

        /** 上下文里没有术语可查；也不落空产物。 */
        SKIP_NO_TERMS,

        /** 触发查证。 */
        VERIFY
    }

    /**
     * 逐条查证并记账：把上下文里的术语全量交给 {@link TermLookupProvider} 联网查证，
     * 按结论分类计数并组装 {@link VerifiedTerm}。触发是确定性的：全量逐条查证，
     * 不做「哪些值得查」的预过滤——模型不觉得自己在猜，没有可靠信号可依据。
     *
     * <p><b>失败与弃权是两条路</b>：查证调用抛异常 = 该词<b>不进产物</b>（记入
     * {@link Outcome#failures()}，下次文件缺失时自然补查——写进去反而会把「没查成」永久固化成
     * 「已查证」）；查过但无有据译名 = {@code official} 空串记账（弃权结论持久化，不再重搜烧钱）。
     *
     * @param context        字幕上下文（术语表的来源；可为降级的空上下文——术语为空时无事可做）
     * @param targetLanguage 目标语种（调用方已在入口保证非空）
     * @param provider       查证端口（调用方已确认在容器里）
     * @return 查证结论：产物候选 + 各类计数 + 失败记账；是否落盘按 {@link Outcome#persistable()}
     */
    public static Outcome verify(SubtitleContext context, String targetLanguage, TermLookupProvider provider) {
        List<VerifiedTerm> verified = new ArrayList<>(context.terms().size());
        List<Outcome.FailedLookup> failures = new ArrayList<>();
        int official = 0;
        int common = 0;
        int none = 0;
        for (SubtitleContext.Term term : context.terms()) {
            try {
                TermLookupResult result =
                        provider.lookup(new TermLookupCommand(term.fixed(), targetLanguage, context.theme()));
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
                verified.add(new VerifiedTerm(term.asr(), term.fixed(), term.note(), name, note));
            } catch (RuntimeException e) {
                // 查证失败 ≠ 查不到：该词不进产物文件（下次补查），绝不因查证挡翻译主流程
                failures.add(new Outcome.FailedLookup(term.fixed(), e.getMessage()));
            }
        }
        return new Outcome(List.copyOf(verified), official, common, none, List.copyOf(failures));
    }

    /**
     * 查证结论：产物候选与记账。{@code verified} 只含查过（含弃权）的词，失败的词在 {@code failures}。
     *
     * @param verified    产物候选（官方 / 通用 / 弃权结论），落盘即它的内容
     * @param official    查到官方译名的条数
     * @param common      查到通用译名（非官方）的条数
     * @param none        查过但弃权（无有据译名）的条数
     * @param failures    查证调用失败的记账（词不进产物，下次补查）
     */
    public record Outcome(List<VerifiedTerm> verified, int official, int common, int none,
                          List<FailedLookup> failures) {

        /**
         * 紧凑构造器兜底：null 一律收敛成空表，调用方不必判空。
         */
        public Outcome {
            verified = verified == null ? List.of() : List.copyOf(verified);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        /**
         * 是否该落盘：官方 / 通用 / 弃权任一结论存在即落；<b>全部失败不落盘</b>——落一份空产物会把
         * 整批词固化成「已查证」，下次永远不会再补查。
         */
        public boolean persistable() {
            return official + common + none > 0;
        }

        /**
         * 一条查证失败的记账：该词不进产物（下次文件缺失时补查）。
         *
         * @param term   术语的正确写法（查证按它发起）
         * @param reason 失败原因（异常消息，供调用方记 WARN）
         */
        public record FailedLookup(String term, String reason) {
        }
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
}
