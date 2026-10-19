package com.vidsage.domain.subtitle.policy;

import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 检查点语义单源 + 跨批上下文政策（纯函数领域政策，不做文件 I/O）。
 *
 * <p><b>检查点语义</b>：只认「JSON 检查点存在且字节数 &gt; 0」，各调用方合流于此判、不各自判断。
 * <b>SRT 是派生视图，不参与幂等</b>——空转写产出 0 字节 SRT，拿 SRT 当检查点会让空产物永远判
 * 「未完成」，陷入反复重跑的死循环；政策因此不接受任何路径/扩展名入参，SRT 大小进不了判定。
 * 产物在但读不动或解析失败，调用方也照未完成重跑：重跑一遍比留一份坏产物好。
 *
 * <p><b>上下文政策</b>：先读缓存（命中不摘要），没有才摘要（{@link #resolveContext}）；
 * 摘要失败时用带降级标记的空上下文，这份标记可以落盘（表示这次试过了），但<b>读回时绝不算缓存命中</b>
 * ——否则摘要失败一次就会被当成「以后都不用再试」，摘要永久丢失且不报错。
 *
 * <p>本类只做裁决，不做 I/O：字节数、文件内容由调用方取好再传进来。
 */
public final class CheckpointPolicy {

    private CheckpointPolicy() {
    }

    /**
     * 检查点是否已完成：字节数 &gt; 0 即完成，0 字节 = 未完成。
     *
     * @param checkpointSizeBytes 检查点产物字节数（调用方量好传入，缺失/非普通文件传 0）
     * @return 非空时为 {@code true}
     */
    public static boolean isCompleted(long checkpointSizeBytes) {
        return checkpointSizeBytes > 0;
    }

    /**
     * 检查点是否「就是当前这一份」：字节数 &gt; 0 <b>且</b>指纹与当前一致。
     *
     * <p>与 {@link #isCompleted} 的分工：那是「产物在不在」，这条是「还对不对得上现在的输入」。
     * 产物在但关键词变了照旧重跑——否则用户改完关键词看到的是一份按旧关键词生成的字幕，且毫无提示。
     *
     * <p><b>存的那份为 {@code null} 一律判 {@code false}</b>（不一致，重跑）：那是与关键词无关的
     * 产物（原始转写），或存量产物里根本没有这一栏。安全方向永远是重跑。
     *
     * @param checkpointSizeBytes 检查点产物字节数（调用方量好传入，缺失/非普通文件传 0）
     * @param storedFingerprint  产物里记着的指纹；{@code null} = 没记（与关键词无关的产物 / 存量产物）
     * @param currentFingerprint 当前这份输入算出的指纹
     * @return 产物非空且指纹一致时为 {@code true}
     */
    public static boolean isCurrent(long checkpointSizeBytes, String storedFingerprint, String currentFingerprint) {
        return isCompleted(checkpointSizeBytes)
                && storedFingerprint != null
                && storedFingerprint.equals(currentFingerprint);
    }

    /**
     * 上下文政策的裁决：用哪份上下文、摘要该不该落盘。
     *
     * @param context 本次实际采用的上下文（缓存命中 = 缓存那份；摘要成功 = 摘要那份；降级 = 空上下文）
     * @param source  结论来源
     */
    public record ContextResolution(SubtitleContext context, Source source) {

        /**
         * 摘要结果是否该按「成功摘要」落盘：仅新摘要出来的上下文为 {@code true}。
         *
         * <p>缓存命中没有新东西可写；降级上下文由调用方自行决定是否落盘（写的是带降级标记的那份，
         * 读回不会命中缓存），所以这里不为它背书。
         */
        public boolean shouldPersistSummary() {
            return source == Source.FRESH_SUMMARY;
        }
    }

    /**
     * 上下文结论来源。
     */
    public enum Source {

        /** 缓存命中：用已有那份，不摘要、不落盘。 */
        CACHE_HIT,

        /** 缓存未命中且摘要成功：采用摘要结果，该落盘。 */
        FRESH_SUMMARY,

        /** 缓存未命中且摘要失败：降级为带标记的空上下文（调用方可以落盘，下次读回仍会重试摘要）。 */
        DEGRADED_EMPTY
    }

    /**
     * 上下文政策：缓存-or-摘要；带降级标记的缓存当作未命中。
     *
     * @param cached    从磁盘读出的缓存上下文；{@code null} = 没有缓存，带降级标记 = 上次没摘出来、重新摘要
     * @param summarize 摘要请求（惰性）；仅在缓存可用时不被调用（必然为空时调用至多一次）
     * @return 采用的上下文与结论来源
     */
    public static ContextResolution resolveContext(SubtitleContext cached,
                                                   Supplier<Optional<SubtitleContext>> summarize) {
        if (cached != null && !cached.degraded()) {
            return new ContextResolution(cached, Source.CACHE_HIT);
        }
        Optional<SubtitleContext> summarized = summarize.get();
        if (summarized != null && summarized.isPresent()) {
            return new ContextResolution(summarized.get(), Source.FRESH_SUMMARY);
        }
        return new ContextResolution(SubtitleContext.degradedEmpty(), Source.DEGRADED_EMPTY);
    }
}
