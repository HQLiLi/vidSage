package com.vidsage.domain.subtitle.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * CheckpointPolicy 的纯单元测试。
 *
 * <p>政策是纯函数、不做文件 I/O：字节数由调用方量好传入，SRT 进不了入参——
 * 「只有 SRT 在而 JSON 不在必须重跑」那条判据在类型上就不可能落到本政策里。
 */
class CheckpointPolicyTest {

    // ------------------------------------------------------------------
    // 检查点判据：JSON 非空 = 完成，0 字节 = 未完成，SRT 是派生视图不参与幂等
    // ------------------------------------------------------------------

    @Test
    void zeroByteCheckpointIsNotComplete() {
        // 空检查点不算「已完成」（hasTranslationArtifactTrueOnlyWhenCheckpointNonEmpty 的空文件分支、
        // treatsZeroByteAudioAsMissing 的域级对应）
        assertFalse(CheckpointPolicy.isCompleted(0), "空检查点不算已完成");
    }

    @Test
    void nonEmptyCheckpointIsComplete() {
        // 非空检查点在 → 已完成（hasTranslationArtifactTrueOnlyWhenCheckpointNonEmpty 的非空分支）
        assertTrue(CheckpointPolicy.isCompleted(1), "非空检查点在 → 已完成");
    }

    // ------------------------------------------------------------------
    // 上下文政策：缓存-or-摘要 + 空摘要绝不落盘（Cleanup / Translation 两处合流）
    // ------------------------------------------------------------------

    @Test
    void cacheHitSkipsSummarize() {
        // contextCacheHitSkipsSummarize 的域级对应：缓存命中绝不触发摘要（连摘要调用都不发生）
        SubtitleContext cached = new SubtitleContext("中世纪剑术", "de", List.of());

        CheckpointPolicy.ContextResolution resolution = CheckpointPolicy.resolveContext(cached,
                () -> {
                    throw new IllegalStateException("缓存命中不应调用摘要");
                });

        assertEquals(cached, resolution.context(), "命中时用的就是缓存那份");
        assertEquals(CheckpointPolicy.Source.CACHE_HIT, resolution.source());
        assertFalse(resolution.shouldPersistSummary(), "缓存命中没有新东西可落盘");
    }

    @Test
    void summarizeSuccessPersistsFreshContext() {
        // summarizeSuccessWritesContextFile 的域级对应：摘要成功应把上下文落盘，且落的就是成功那份
        SubtitleContext fresh = new SubtitleContext("中世纪剑术", "de", List.of());

        CheckpointPolicy.ContextResolution resolution = CheckpointPolicy.resolveContext(null,
                () -> Optional.of(fresh));

        assertEquals(fresh, resolution.context(), "落盘的该是成功那份上下文");
        assertEquals(CheckpointPolicy.Source.FRESH_SUMMARY, resolution.source());
        assertTrue(resolution.shouldPersistSummary(), "摘要成功应把上下文落盘");
    }

    @Test
    void summarizeDegradeReturnsMarkedEmptyContext() {
        // 降级出的空上下文带降级标记：调用方可以落盘（表示这次试过了），但读回不会命中缓存
        CheckpointPolicy.ContextResolution resolution = CheckpointPolicy.resolveContext(null,
                () -> Optional.empty());

        assertEquals(SubtitleContext.degradedEmpty(), resolution.context(), "降级用带标记的空上下文");
        assertTrue(resolution.context().degraded(), "降级结果的标记必须是 true");
        assertEquals(CheckpointPolicy.Source.DEGRADED_EMPTY, resolution.source());
        assertFalse(resolution.shouldPersistSummary(), "政策不为降级落盘背书，是否写由调用方定");
    }

    @Test
    void degradedCacheIsTreatedAsMiss() {
        // 上次没摘出来留下的空壳：读回时绝不能当缓存命中，否则摘要永久丢失且不报错
        SubtitleContext degradedCache = SubtitleContext.degradedEmpty();
        SubtitleContext fresh = new SubtitleContext("中世纪剑术", "de", List.of());

        CheckpointPolicy.ContextResolution resolution = CheckpointPolicy.resolveContext(degradedCache,
                () -> Optional.of(fresh));

        assertEquals(CheckpointPolicy.Source.FRESH_SUMMARY, resolution.source(), "带降级标记的缓存当作未命中");
        assertEquals(fresh, resolution.context(), "重新摘要出来的那份才是本次要用的");
    }

    // ------------------------------------------------------------------
    // 检查点「就是当前这一份」：字节数非空 + 指纹一致
    // ------------------------------------------------------------------

    @Test
    void currentWhenNonEmptyAndFingerprintMatches() {
        assertTrue(CheckpointPolicy.isCurrent(2048, "abc123", "abc123"),
                "产物非空且指纹一致 = 这份字幕就是当前关键词生成的");
    }

    @Test
    void notCurrentWhenFingerprintDiffers() {
        assertFalse(CheckpointPolicy.isCurrent(2048, "abc123", "999999"),
                "关键词变了就该重跑，否则用户改完关键词看到的还是按旧关键词生成的字幕");
    }

    @Test
    void notCurrentWhenStoredFingerprintMissing() {
        assertFalse(CheckpointPolicy.isCurrent(2048, null, "abc123"),
                "存量产物没有这一栏 / 与关键词无关的产物，一律判不一致——安全方向永远是重跑");
    }

    @Test
    void notCurrentWhenArtifactEmpty() {
        assertFalse(CheckpointPolicy.isCurrent(0, "abc123", "abc123"),
                "0 字节仍按未完成处理，指纹一致也救不回来");
    }
}
