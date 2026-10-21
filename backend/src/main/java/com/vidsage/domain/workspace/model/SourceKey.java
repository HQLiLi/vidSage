package com.vidsage.domain.workspace.model;

import org.springframework.util.StringUtils;

/**
 * 媒体身份键值对象：同一份内容必须算出同一个键，据此认领同一个 {@code workspace}。
 *
 * <p>两条路线：URL 路线是 {@code bvid|p}（B 站视频号 + 分 P）；上传路线是内容 sha256。
 * <b>形态构造</b>（剥跟踪参数、取 {@code BV...}、{@code av} 换算、短链跟跳转、sha256 判定）
 * 属于 URL 规范化策略，不在这里。
 *
 * <p>唯一的硬不变量是「非空白 + 不超 {@code workspace.source_key} 的列宽（512）」——
 * 刻意<b>不做 bvid / sha256 的正则强校验</b>：键还会经 {@code restore} 从数据库行还原，
 * 存量数据与测试夹具的取值形态不应被构造器拒之门外。
 *
 * <p>流转边界：只在 domain ↔ application ↔ persistence 之间流转，落库时取 {@link #value()}。
 *
 * @param value 身份键（URL 路线 {@code bvid|p}；上传路线内容 sha256）
 */
public record SourceKey(String value) {

    /** 数据库列 {@code workspace.source_key} 的宽度上限（VARCHAR(512)）。 */
    public static final int MAX_LENGTH = 512;

    /**
     * 校验硬不变量：身份键非空白、且不超列宽。空白说明边界卫语句漏了（空键一路传到领域层），
     * 超长说明形态构造侧漏了校验，两者都属服务端缺陷。
     *
     * @throws IllegalStateException value 为 {@code null} / 空白，或长度超过 {@link #MAX_LENGTH}
     */
    public SourceKey {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("sourceKey 不能为空白：身份键是 URL 路线的 bvid|p "
                    + "或上传路线的内容 sha256，请先算出身份键再构造");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalStateException("sourceKey 长度超过列宽 " + MAX_LENGTH + "：当前 " + value.length()
                    + " 字符，落不进 workspace.source_key（VARCHAR(512)）");
        }
    }
}
