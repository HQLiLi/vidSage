package com.vidsage.domain.subtitle.model.term;

/**
 * 一次术语译名查证的结论。
 *
 * <p><b>查不到 ≠ 失败</b>：{@code officialName} 为空串是「查过、没有有据可依的译名」（弃权结论，
 * 上层会持久化、不再重搜）；调用异常才是失败（上层让该术语保持未查证、下次补跑），异常直接抛出、
 * 不用本类型表达。
 *
 * @param officialName 查到的目标语种译名；查不到为<b>空串</b>（不是 {@code null}，契约上用空串表达「无」）
 * @param official     {@code true} = 官方译名（官方渠道给出）；{@code false} = 通用译名（多个独立来源
 *                     一致使用但非官方）。仅在 {@code officialName} 非空时有意义
 * @param sourceUrl    证据来源页 URL；接地校验的凭据之一，查不到为空串
 * @param quote        来源页中含该译名的原文引文；接地校验的凭据之一，查不到为空串
 */
public record TermLookupResult(String officialName, boolean official, String sourceUrl, String quote) {

    /**
     * 「查不到」的规范结论：译名与证据全空。实现方查不到时返回它，不要自己拼空对象。
     */
    public static TermLookupResult none() {
        return new TermLookupResult("", false, "", "");
    }
}
