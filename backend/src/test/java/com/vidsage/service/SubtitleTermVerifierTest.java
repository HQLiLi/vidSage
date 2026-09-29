package com.vidsage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.model.subtitle.SubtitleContext;
import com.vidsage.model.subtitle.TermLookupCommand;
import com.vidsage.model.subtitle.TermLookupResult;
import com.vidsage.provider.termlookup.TermLookupProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * SubtitleTermVerifier 的纯单元测试：<b>不起 Spring 容器</b>。
 *
 * <p>重点钉住三件事：<b>null/空串三态语义</b>（未查证 / 弃权 / 查到——旧缓存迁移靠它判定）、
 * <b>失败与弃权分流</b>（异常保持 null 待补跑，查不到持久化空串不再重搜）、
 * <b>触发条件的每一档都不发调用</b>（同语言 / 空目标 / 无术语 / 全查证 / provider 缺席）。
 */
class SubtitleTermVerifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 按术语名返回预设结论的桩，同时记录收到的命令。 */
    private final List<TermLookupCommand> commands = new java.util.ArrayList<>();

    private TermLookupProvider stub(String... behavior) {
        return command -> {
            commands.add(command);
            // behavior 按 [术语名, 结论] 成对给出；没给到的术语一律抛异常（模拟查证失败）
            for (int i = 0; i + 1 < behavior.length; i += 2) {
                if (behavior[i].equals(command.term())) {
                    String verdict = behavior[i + 1];
                    if (verdict.startsWith("throw")) {
                        throw new IllegalStateException("模拟查证失败");
                    }
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

    private static ObjectProvider<TermLookupProvider> present(TermLookupProvider p) {
        return new ObjectProvider<>() {
            @Override
            public TermLookupProvider getObject() {
                return p;
            }

            @Override
            public TermLookupProvider getIfAvailable() {
                return p;
            }
        };
    }

    private static ObjectProvider<TermLookupProvider> absent() {
        return mock(ObjectProvider.class);
    }

    private SubtitleContext context(String sourceLanguage, SubtitleContext.Term... terms) {
        return new SubtitleContext("游戏解析", sourceLanguage, List.of(terms));
    }

    // ------------------------------------------------------------------
    // 三态语义与分流
    // ------------------------------------------------------------------

    @Test
    void officialCommonNoneAndFailureAreSeparated() {
        SubtitleContext in = context("zh",
                new SubtitleContext.Term("只狼", "只狼", "", null, null),
                new SubtitleContext.Term("架势条", "架势条", "", null, null),
                new SubtitleContext.Term("弹刀", "弹刀", "", null, null),
                new SubtitleContext.Term("生 hard", "生hard", "", null, null));
        SubtitleTermVerifier verifier = new SubtitleTermVerifier(present(stub(
                "只狼", "official:Sekiro",
                "架势条", "posture meter",
                "弹刀", "")));

        SubtitleTermVerifier.Result r = verifier.verifyIfNeeded(in, "en");

        assertTrue(r.changed());
        assertEquals(4, r.lookedUp());
        assertEquals(1, r.officialCount());
        assertEquals(1, r.commonCount());
        assertEquals(1, r.noneCount());
        assertEquals(1, r.failedCount());

        List<SubtitleContext.Term> out = r.context().terms();
        assertEquals("Sekiro", out.get(0).official());
        assertTrue(out.get(0).officialNote().contains("官方译名"));
        assertTrue(out.get(0).officialNote().contains("example.com"));
        assertEquals("posture meter", out.get(1).official());
        assertTrue(out.get(1).officialNote().contains("通用译名"));
        // 弃权：空串持久化，不再重搜
        assertEquals("", out.get(2).official());
        // 失败：保持 null，下次补跑
        assertNull(out.get(3).official());
        // 查证命令按 fixed 写法发起，带主题与目标语种
        assertEquals("只狼", commands.get(0).term());
        assertEquals("en", commands.get(0).targetLanguage());
        assertEquals("游戏解析", commands.get(0).theme());
    }

    @Test
    void alreadyVerifiedTermsAreNotReLookedUp() {
        SubtitleContext in = context("zh",
                new SubtitleContext.Term("只狼", "只狼", "", "Sekiro", "官方译名"),
                new SubtitleContext.Term("弹刀", "弹刀", "", "", "弃权"));
        SubtitleTermVerifier verifier = new SubtitleTermVerifier(present(stub()));

        SubtitleTermVerifier.Result r = verifier.verifyIfNeeded(in, "en");

        assertFalse(r.changed());
        assertSame(in, r.context());
        assertTrue(commands.isEmpty());
    }

    @Test
    void oldCacheDeserializesOfficialAsNull() {
        // 查证上线前落盘的 context：JSON 里没有 official 键 → 反序列化必须是 null（= 未查证）
        String legacyJson = "{\"theme\":\"主题\",\"sourceLanguage\":\"zh\","
                + "\"terms\":[{\"asr\":\"只狼\",\"fixed\":\"只狼\",\"note\":\"游戏名\"}]}";
        SubtitleContext legacy = JSON.readValue(legacyJson, SubtitleContext.class);
        assertNull(legacy.terms().get(0).official());

        // 而新落盘的弃权结论（空串）round-trip 后仍是空串，不会被吞成 null
        SubtitleContext abstained = context("zh", new SubtitleContext.Term("弹刀", "弹刀", "", "", "弃权"));
        String json = JSON.writeValueAsString(abstained);
        assertEquals("", JSON.readValue(json, SubtitleContext.class).terms().get(0).official());
    }

    // ------------------------------------------------------------------
    // 触发条件：每一档都不发调用
    // ------------------------------------------------------------------

    @Test
    void skipsWhenLanguagesMatch() {
        SubtitleContext in = context("zh", new SubtitleContext.Term("只狼", "只狼", "", null, null));
        SubtitleTermVerifier verifier = new SubtitleTermVerifier(present(stub("只狼", "official:Sekiro")));

        SubtitleTermVerifier.Result r = verifier.verifyIfNeeded(in, "zh");

        assertFalse(r.changed());
        assertTrue(commands.isEmpty());
    }

    @Test
    void skipsWhenLanguageVariantMatchesMainSubtag() {
        SubtitleContext in = context("zh-CN", new SubtitleContext.Term("只狼", "只狼", "", null, null));
        SubtitleTermVerifier verifier = new SubtitleTermVerifier(present(stub("只狼", "official:Sekiro")));

        assertFalse(verifier.verifyIfNeeded(in, "ZH").changed());
        assertTrue(commands.isEmpty());
    }

    @Test
    void skipsWhenTargetBlank() {
        SubtitleContext in = context("zh", new SubtitleContext.Term("只狼", "只狼", "", null, null));
        SubtitleTermVerifier verifier = new SubtitleTermVerifier(present(stub("只狼", "official:Sekiro")));

        assertFalse(verifier.verifyIfNeeded(in, "  ").changed());
        assertTrue(commands.isEmpty());
    }

    @Test
    void skipsWhenNoTermsOrNullContext() {
        SubtitleTermVerifier verifier = new SubtitleTermVerifier(present(stub()));
        assertFalse(verifier.verifyIfNeeded(null, "en").changed());
        assertFalse(verifier.verifyIfNeeded(context("zh"), "en").changed());
        assertTrue(commands.isEmpty());
    }

    @Test
    void skipsWhenProviderAbsent() {
        SubtitleContext in = context("zh", new SubtitleContext.Term("只狼", "只狼", "", null, null));
        SubtitleTermVerifier verifier = new SubtitleTermVerifier(absent());

        SubtitleTermVerifier.Result r = verifier.verifyIfNeeded(in, "en");

        assertFalse(r.changed());
        assertSame(in, r.context());
    }
}
