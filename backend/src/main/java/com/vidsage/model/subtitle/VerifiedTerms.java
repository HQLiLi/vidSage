package com.vidsage.model.subtitle;

import java.util.List;

/**
 * 术语查证产物的容器：{@code terms-<route>-<target>.json} 文件的读写形状（见
 * {@code SubtitleArtifactNames#verifiedTermsFileName}）。
 *
 * @param terms 查证后的术语表（见 {@link VerifiedTerm}）；可为 {@code null}，紧凑构造器收敛为空表
 */
public record VerifiedTerms(List<VerifiedTerm> terms) {

    /**
     * 紧凑构造器兜底：null 一律收敛成空表，调用方不必判空。
     */
    public VerifiedTerms {
        terms = terms == null ? List.of() : List.copyOf(terms);
    }
}
