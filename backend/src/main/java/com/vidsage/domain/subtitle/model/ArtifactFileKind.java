package com.vidsage.domain.subtitle.model;

import com.vidsage.domain.subtitle.model.common.SubtitleArtifactSpec;

import java.util.Arrays;
import java.util.Optional;

/**
 * 一版产物里能有的四个文件位：检查点 JSON、SRT 视图、摘要副产物、术语表。
 *
 * <p>每个文件位有两套名字，各管一处，不互相推导：{@link #key()} 是产物清单里 {@code files}
 * 的键，{@link #token()} 是下载 URL 里的文件名段（对外固定带扩展名）。
 *
 * <p>{@link #belongsTo} 与 {@link #specFor} 穷举列全三种产物类型：将来新增类型时编译期就会
 * 报缺分支，而不是悄悄少一个文件位。
 */
public enum ArtifactFileKind {

    /** 检查点 JSON：转写 / 清洗 / 翻译的 segments。 */
    SEGMENTS("segments", "segments.json"),

    /** SRT 派生视图。 */
    SUBTITLES("subtitles", "subtitles.srt"),

    /** 清洗的摘要副产物，只有 CLEANED 有。 */
    CONTEXT("context", "context.json"),

    /** 术语查证结论，只有 TRANSLATED 有。 */
    TERMS("terms", "terms.json");

    private final String key;
    private final String token;

    ArtifactFileKind(String key, String token) {
        this.key = key;
        this.token = token;
    }

    /** 产物清单里 {@code files} 的键。 */
    public String key() {
        return key;
    }

    /** 下载 URL 里的文件名段。 */
    public String token() {
        return token;
    }

    /**
     * 按下载 URL 的文件名段反解文件位。
     *
     * @param token 文件名段，如 {@code segments.json}
     * @return 对应的文件位；不认识的段为空
     */
    public static Optional<ArtifactFileKind> fromToken(String token) {
        return Arrays.stream(values()).filter(kind -> kind.token.equals(token)).findFirst();
    }

    /**
     * 这种产物类型是否带这个文件位。
     *
     * @param type 产物类型
     * @return 该类型应带此文件位时为 {@code true}
     */
    public boolean belongsTo(ArtifactType type) {
        return switch (type) {
            case ORIGINAL -> this == SEGMENTS || this == SUBTITLES;
            case CLEANED -> this == SEGMENTS || this == SUBTITLES || this == CONTEXT;
            case TRANSLATED -> this == SEGMENTS || this == SUBTITLES || this == TERMS;
        };
    }

    /**
     * 这种产物类型下，本文件位对应的文件名模板。
     *
     * @param type 产物类型
     * @return 该类型下本文件位的模板
     * @throws IllegalStateException 该类型不带这个文件位
     */
    public SubtitleArtifactSpec specFor(ArtifactType type) {
        return switch (this) {
            case SEGMENTS -> switch (type) {
                case ORIGINAL -> SubtitleArtifactSpec.ORIGINAL_SEGMENTS;
                case CLEANED -> SubtitleArtifactSpec.CLEAN_SEGMENTS;
                case TRANSLATED -> SubtitleArtifactSpec.TRANSLATED_SEGMENTS;
            };
            case SUBTITLES -> switch (type) {
                case ORIGINAL -> SubtitleArtifactSpec.ORIGINAL_SRT;
                case CLEANED -> SubtitleArtifactSpec.CLEAN_SRT;
                case TRANSLATED -> SubtitleArtifactSpec.TRANSLATED_SRT;
            };
            case CONTEXT -> {
                if (type != ArtifactType.CLEANED) {
                    throw new IllegalStateException("产物类型 " + type + " 不带 " + name() + " 文件位");
                }
                yield SubtitleArtifactSpec.CONTEXT;
            }
            case TERMS -> {
                if (type != ArtifactType.TRANSLATED) {
                    throw new IllegalStateException("产物类型 " + type + " 不带 " + name() + " 文件位");
                }
                yield SubtitleArtifactSpec.TERMS;
            }
        };
    }
}
