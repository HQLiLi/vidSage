package com.vidsage.domain.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.subtitle.TermVerification.Decision;
import com.vidsage.domain.subtitle.TermVerification.Outcome;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * TermVerification 的纯单元测试：术语查证政策断言随逻辑自 {@code SubtitleTranslationServiceTest}
 * 迁入，断言值不变（2026-10-06 Phase B，触发三条件与结论记账下沉为本类公开 API）。
 *
 * <p>触发判定走 {@link TermVerification#decide}（纯事实入参），执行与记账走
 * {@link TermVerification#verify}（provider 为桩，不联网）；落盘本身留在应用层，这里只断
 * {@link Outcome#persistable()}。
 */
class TermVerificationTest {

    // ------------------------------------------------------------------
    // 触发判定（decide）：三条同时成立才查，否则静默跳过
    // ------------------------------------------------------------------

    @Test
    void sameLanguageDoesNotLookUp() {
        assertEquals(Decision.SKIP_SAME_LANGUAGE,
                TermVerification.decide(true, false, true, true),
                "同语种不翻译，也就不查证——目标语种默认 zh 的中文素材不烧搜索费");
    }

    @Test
    void existingTermsFileSkipsLookup() {
        assertEquals(Decision.SKIP_ALREADY_VERIFIED,
                TermVerification.decide(false, true, true, true),
                "terms 产物已存在 = 已查证，零查证调用（删文件即重查）");
    }

    @Test
    void providerAbsentSkipsLookup() {
        assertEquals(Decision.SKIP_LOOKUP_DISABLED,
                TermVerification.decide(false, false, false, true),
                "查证是增益不是前提，配置缺席整体关闭、跳过不报错");
    }

    @Test
    void noTermsSkipsLookup() {
        assertEquals(Decision.SKIP_NO_TERMS,
                TermVerification.decide(false, false, true, false),
                "无术语可查；也不落空产物——空产物没有信息量，徒增一个「已完成」假象");
    }

    @Test
    void differentLanguagesWithTermsAndProviderVerifies() {
        assertEquals(Decision.VERIFY, TermVerification.decide(false, false, true, true));
    }

    @Test
    void sameLanguageShortCircuitsBeforeArtifactCheck() {
        // 判定顺序即旧守卫顺序：同语种优先于「产物已存在」，短路语义与迁移前一致
        assertEquals(Decision.SKIP_SAME_LANGUAGE, TermVerification.decide(true, true, true, true));
    }

    // ------------------------------------------------------------------
    // 执行与记账（verify）：official/common/none 分类、失败词不进产物
    // ------------------------------------------------------------------

    /** 一份带三条术语的上下文：官方可查 / 弃权 / 失败各占一个名额。 */
    private static SubtitleContext contextWithTerms() {
        return new SubtitleContext("游戏动作解析", "en", List.of(
                new SubtitleContext.Term("只狼", "只狼", "游戏名"),
                new SubtitleContext.Term("弹刀", "弹刀", "机制名"),
                new SubtitleContext.Term("架势条", "架势条", "UI 元素")));
    }

    /** 查证 provider 收到的命令——「全量逐条 / 零调用」这类断言靠它。 */
    private final List<TermLookupCommand> lookupCommands = new ArrayList<>();

    /**
     * 查证 provider 的桩：按术语名返回预设结论，behavior 按 [术语名, 结论] 成对给出；
     * 没给到的术语一律抛异常（模拟查证调用失败）。结论写法：
     * {@code "official:Sekiro"} = 官方译名、{@code "posture meter"} = 通用译名、
     * {@code ""} = 弃权（none）。
     */
    private TermLookupProvider stubLookup(String... behavior) {
        return command -> {
            lookupCommands.add(command);
            for (int i = 0; i + 1 < behavior.length; i += 2) {
                if (behavior[i].equals(command.term())) {
                    String verdict = behavior[i + 1];
                    if (verdict.isEmpty()) {
                        return TermLookupResult.none();
                    }
                    boolean official = verdict.startsWith("official:");
                    String name = official ? verdict.substring("official:".length()) : verdict;
                    return new TermLookupResult(name, official, "https://example.com/wiki", "引文 " + name);
                }
            }
            throw new IllegalStateException("模拟查证失败");
        };
    }

    @Test
    void verifiesAllTermsAndClassifiesConclusions() {
        // 只狼=官方、弹刀=弃权（查过无据）、架势条=没配结论（调用失败）——三种结论一次钉住
        Outcome outcome = TermVerification.verify(contextWithTerms(), "zh", stubLookup(
                "只狼", "official:Sekiro",
                "弹刀", ""));

        // 触发是确定性的：全量逐条查证，不做「哪些值得查」的预过滤
        assertEquals(3, lookupCommands.size());
        assertEquals("只狼", lookupCommands.getFirst().term(), "查证命令按 fixed 写法发起");
        assertEquals("zh", lookupCommands.getFirst().targetLanguage());
        assertEquals("游戏动作解析", lookupCommands.getFirst().theme());
        // 结论记账：官方带等级与来源 host、弃权空串持久化、失败词不进产物
        assertEquals(2, outcome.verified().size(), "调用失败的词不进产物文件");
        VerifiedTerm officialHit = outcome.verified().getFirst();
        assertEquals("Sekiro", officialHit.official());
        assertTrue(officialHit.officialNote().contains("官方译名"), "note 记译名等级");
        assertTrue(officialHit.officialNote().contains("example.com"), "note 记来源 host，不记完整 URL");
        VerifiedTerm abstained = outcome.verified().get(1);
        assertEquals("", abstained.official(), "弃权 = 空串落盘，不再重搜");
        assertTrue(abstained.officialNote().contains("弃权"));
        assertEquals(1, outcome.official(), "官方计 1");
        assertEquals(0, outcome.common());
        assertEquals(1, outcome.none(), "弃权计 1");
        assertEquals(1, outcome.failures().size(), "失败单独记账");
        assertEquals("架势条", outcome.failures().getFirst().term());
        assertTrue(outcome.persistable(), "有结论即该落盘");
    }

    @Test
    void allLookupsFailYieldsNothingToPersist() {
        // 桩没给任何术语配结论 → 全部抛异常 = 全部查证失败
        Outcome outcome = TermVerification.verify(contextWithTerms(), "zh", stubLookup());

        assertFalse(outcome.persistable(),
                "全部失败不落产物——空产物会把整批词固化成「已查证」，下次永远不会再补查");
        assertEquals(0, outcome.verified().size());
        assertEquals(3, outcome.failures().size());
    }

    @Test
    void commonVerdictCountsAsPersistableConclusion() {
        // 通用译名（非官方）也是可持久化结论，与官方同路记账
        Outcome outcome = TermVerification.verify(contextWithTerms(), "zh",
                stubLookup("只狼", "posture meter"));

        assertEquals(1, outcome.common(), "通用译名单独计数");
        assertEquals(0, outcome.official());
        assertTrue(outcome.verified().getFirst().officialNote().contains("通用译名"));
        assertTrue(outcome.persistable());
    }
}
