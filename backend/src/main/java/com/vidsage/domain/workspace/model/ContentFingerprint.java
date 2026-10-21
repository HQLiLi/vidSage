package com.vidsage.domain.workspace.model;

import java.time.LocalDateTime;

/**
 * 媒体内容指纹：媒体时长 + 发布时间两列的组合，用来判断同一个身份下的内容变了没有。
 *
 * <p>两列都必须有值才算一次成功的探测——只拿到一半没法可靠比对，调用方应把这种情况当成
 * 「取不到指纹」处理（返回 {@link java.util.Optional#empty()}），而不是硬造一个半截指纹。
 *
 * <p>{@code publishedAt} 按 UTC 落库。它不对外展示，只用于跨 run 比对；用 UTC 是为了让同一份内容
 * 在任何时区的机器上都算出同一个值，不会因为换了机器 / 夏令时把没变的内容判成变了。
 *
 * @param durationMs  媒体时长（毫秒），从 0 起算，不是时间戳
 * @param publishedAt 媒体发布时间（来自下载器元数据的 epoch 秒，按 UTC 转成墙钟）
 */
public record ContentFingerprint(int durationMs, LocalDateTime publishedAt) {

    /**
     * 校验硬不变量：时长非负、发布时间非空。
     *
     * <p>本记录由服务端自己从下载器元数据算出，触发即说明探测侧漏了校验——这是最后防线，
     * 抛 {@link IllegalStateException}（不是调用方传参错误）。
     *
     * @throws IllegalStateException 时长为负，或发布时间为空
     */
    public ContentFingerprint {
        if (durationMs < 0) {
            throw new IllegalStateException("内容指纹的时长不能为负：当前为 " + durationMs
                    + "；取不到合法时长时应返回 Optional.empty()，不要构造指纹");
        }
        if (publishedAt == null) {
            throw new IllegalStateException("内容指纹的发布时间不能为空：取不到发布时间时应返回 Optional.empty()，"
                    + "不要构造指纹");
        }
    }
}
