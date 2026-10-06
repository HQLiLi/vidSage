package com.vidsage.domain.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * VerifiedTerms / VerifiedTerm 的序列化 round-trip：terms 产物文件的读写形状必须对称——
 * 落盘什么、读回来还是什么（翻译侧任务按 official 字段渲染 terms 干预参数，形状漂移会静默丢数据）。
 */
class VerifiedTermsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void roundTripKeepsAllFields() {
        VerifiedTerms in = new VerifiedTerms(List.of(
                new VerifiedTerm("只狼", "只狼", "游戏名",
                        "Sekiro: Shadows Die Twice", "官方译名（来源：store.steampowered.com）"),
                new VerifiedTerm("弹刀", "弹刀", "机制名",
                        "", "联网查证未找到有据可依的目标语种译名，弃权")));

        VerifiedTerms out = JSON.readValue(JSON.writeValueAsString(in), VerifiedTerms.class);

        assertEquals(in, out);
        // 弃权的空串 round-trip 后仍是空串，不会被吞成 null（official 无三态的地基）
        assertEquals("", out.terms().get(1).official());
    }

    @Test
    void nullTermsCollapsesToEmptyList() {
        // 上游坏数据 / 手工改文件把 terms 写成 null：紧凑构造器收敛成空表，调用方不必判空
        VerifiedTerms out = JSON.readValue("{\"terms\":null}", VerifiedTerms.class);

        assertEquals(List.of(), out.terms());
        assertEquals(new VerifiedTerms(null), out);
    }
}
