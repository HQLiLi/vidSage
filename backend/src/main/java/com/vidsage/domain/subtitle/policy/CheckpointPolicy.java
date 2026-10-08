package com.vidsage.domain.subtitle.policy;

import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 检查点语义单源 + 跨批上下文政策（纯函数领域政策，不做文件 I/O）。
 *
 * <p><b>检查点语义（SubtitlePreparationCoordinator / SubtitleCleanupCoordinator / SubtitleTranslationCoordinator /
 * AudioPreparationCoordinator 四处合流于此）</b>：
 * <ul>
 *   <li><b>JSON 非空 = 完成</b>：检查点产物存在且字节数 &gt; 0 即该阶段已完成（{@link #isCompleted}）；</li>
 *   <li><b>0 字节 = 未完成</b>：空文件视为不存在，照未完成重跑；</li>
 *   <li><b>SRT 是派生视图，不参与幂等</b>：为什么检查点是 JSON 而不是 SRT——空转写产出 0 字节 SRT，
 *       拿 SRT 当检查点会让空产物永远判「未完成」→ 反复重跑的死循环。本判据只接受 JSON 检查点的
 *       字节数：政策不接受任何路径/扩展名入参，SRT 大小进不了判定</li>
 *   <li>文件在但解析失败：当未完成重跑（重新跑一遍比留一个坏产物好）——该机制的 I/O 与解析留在应用层，
 *       本政策只承载判据。</li>
 * </ul>
 *
 * <p><b>上下文政策（SubtitleCleanupCoordinator 与 SubtitleTranslationCoordinator 两处近逐字重复，合流于此）</b>：
 * 先读缓存（缓存命中不摘要），没有才摘要（{@link #resolveContext}）；<b>摘要成功才落盘，降级出的
 * 空上下文绝不落盘</b>——否则下次会命中这份空缓存，摘要永久丢失且不报错（design 决策 5）。
 * 摘要请求经 {@code Supplier} 惰性传入：缓存命中时连摘要调用都不会发生。
 *
 * <p>判据是域规则，量字节、读文件、写文件都是应用层的事：调用方以 {@code ArtifactFiles.sizeOrZero}
 * 等技术原语取事实，再把事实交给本类判定。
 */
public final class CheckpointPolicy {

    private CheckpointPolicy() {
    }

    /**
     * 检查点是否已完成：字节数 &gt; 0 即完成，0 字节 = 未完成。
     *
     * <p>语义单源在本类（见类注释）：四处服务只调用、不自行判断；SRT 永远不是判据入参。
     *
     * @param checkpointSizeBytes 检查点产物字节数（调用方量好传入，缺失/非普通文件传 0）
     * @return 非空时为 {@code true}
     */
    public static boolean isCompleted(long checkpointSizeBytes) {
        return checkpointSizeBytes > 0;
    }

    /**
     * 上下文政策的裁决：用哪份上下文、摘要该不该落盘。
     *
     * @param context 本次实际采用的上下文（缓存命中 = 缓存那份；摘要成功 = 摘要那份；降级 = 空上下文）
     * @param source  结论来源
     */
    public record ContextResolution(SubtitleContext context, Source source) {

        /**
         * 摘要结果是否该落盘：仅「摘要成功的新上下文」为 {@code true}——
         * 缓存命中没有新东西可写，降级的空上下文绝不能写文件（否则下次会命中这份空缓存）。
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

        /** 缓存未命中且摘要失败：降级为空上下文，且绝不落盘。 */
        DEGRADED_EMPTY
    }

    /**
     * 上下文政策：缓存-or-摘要 + 空摘要绝不落盘。
     *
     * <p>摘要请求惰性求值——只在缓存未命中时才调一次 {@code summarize}（翻译依赖 -clean，
     * 清洗必定先跑并落下上下文，主路径通常命中缓存，命中时连摘要调用都不发生）。
     *
     * @param cached    从磁盘读出的缓存上下文；{@code null} = 缓存未命中
     * @param summarize 摘要请求（惰性）；仅在缓存未命中时被调用至多一次
     * @return 采用的上下文与是否该落盘摘要
     */
    public static ContextResolution resolveContext(SubtitleContext cached,
                                                   Supplier<Optional<SubtitleContext>> summarize) {
        if (cached != null) {
            return new ContextResolution(cached, Source.CACHE_HIT);
        }
        Optional<SubtitleContext> summarized = summarize.get();
        if (summarized != null && summarized.isPresent()) {
            return new ContextResolution(summarized.get(), Source.FRESH_SUMMARY);
        }
        return new ContextResolution(SubtitleContext.empty(), Source.DEGRADED_EMPTY);
    }
}
