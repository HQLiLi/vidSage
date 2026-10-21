package com.vidsage.domain.subtitle.model.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SubtitleStageName 区间累加的单元测试：钉住「按声明顺序累加算出的任务级区间」，
 * 以及翻译不在权重表里、查询区间即抛。
 */
class SubtitleStageNameTest {

    /** 主管线六阶段，按声明顺序。 */
    private static final List<SubtitleStageName> MAIN_STAGES = List.of(
            SubtitleStageName.PROBE_FINGERPRINT,
            SubtitleStageName.ACQUIRE_MEDIA,
            SubtitleStageName.EXTRACT_AUDIO,
            SubtitleStageName.TRANSCRIBE_AUDIO,
            SubtitleStageName.WRITE_SUBTITLES,
            SubtitleStageName.CLEAN_SUBTITLES);

    /** 期望区间 [起点, 终点)，按权重 5/42/42/49/7/60 累加。 */
    private static final int[][] EXPECTED_RANGES = {
            {0, 2}, {2, 22}, {22, 43}, {43, 67}, {67, 70}, {70, 100}};

    @Test
    void rangesMatchDeclaredWeights() {
        for (int i = 0; i < MAIN_STAGES.size(); i++) {
            SubtitleStageName stage = MAIN_STAGES.get(i);
            assertEquals(EXPECTED_RANGES[i][0], stage.start(), stage + " 起点");
            assertEquals(EXPECTED_RANGES[i][1], stage.end(), stage + " 终点");
        }
    }

    @Test
    void rangesAreContiguousMonotonicAndCoverFullScale() {
        assertEquals(0, MAIN_STAGES.getFirst().start(), "首阶段起点必须是 0");
        assertEquals(100, MAIN_STAGES.getLast().end(), "末阶段终点必须是 100——翻译的份额不进分母");
        for (int i = 1; i < MAIN_STAGES.size(); i++) {
            SubtitleStageName prev = MAIN_STAGES.get(i - 1);
            SubtitleStageName cur = MAIN_STAGES.get(i);
            assertEquals(prev.end(), cur.start(),
                    prev + " 终点必须等于 " + cur + " 起点（相邻连通、单调不重叠）");
            assertTrue(cur.end() > cur.start(), cur + " 区间必须非空");
        }
    }

    @Test
    void translationHasNoRangeAndThrowsOnQuery() {
        assertEquals(0, SubtitleStageName.TRANSLATE_SUBTITLES.getWeight());
        assertFalse(SubtitleStageName.TRANSLATE_SUBTITLES.isInMainPipeline(),
                "翻译不在主管线，不参与任务级分配");
        assertThrows(IllegalStateException.class, () -> SubtitleStageName.TRANSLATE_SUBTITLES.start(),
                "翻译没有任务级区间，查询即抛");
        assertThrows(IllegalStateException.class, () -> SubtitleStageName.TRANSLATE_SUBTITLES.end(),
                "翻译没有任务级区间，查询即抛");
    }
}
