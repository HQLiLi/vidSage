package com.vidsage.domain.workspace;

import com.vidsage.domain.workspace.model.ContentFingerprint;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 媒体身份实体：一个媒体来源在库里的身份与内容指纹。
 *
 * <p>内容指纹就是 {@code durationMs} + {@code publishedAt} 两个可读列的组合，
 * 不另拼一列串；两列的写入时机由指纹探测阶段决定，<b>不是提交时写</b>——
 * 提交路径手里只有 URL，拿不到指纹。
 *
 * <p>只有一个变更入口 {@link #updateFingerprint}。{@code id} 是数据库内部行 id（persistence
 * 层回填），<b>不对外</b>。重建已落库的行只能走 {@link #restore}。
 */
@Getter
public class Workspace {

    /** 数据库内部行 id；insert 回填，新建时在落库前为 {@code null}。 */
    private Long id;

    /** 来源类型（URL / UPLOAD）。 */
    private final SourceType sourceType;

    /** 身份键（URL 路线 bvid|p；上传路线内容 sha256），与 sourceType 一起构成唯一键。 */
    private final SourceKey sourceKey;

    /**
     * 来源：URL 原文或上传文件原始名。
     *
     * <p><b>约束：本字段值不得直接写入日志</b>——含用户原始文件名，属敏感信息。
     */
    private final String sourceUri;

    /** 规范化后的 URL（只给人看，不参与身份判定）。 */
    private final String canonicalUrl;

    /** 内容指纹之一：媒体时长（毫秒）；URL 路线才有，UPLOAD 与首跑前为 {@code null}。 */
    private Integer durationMs;

    /** 内容指纹之二：发布时间（来自 yt-dlp 的 timestamp）；取不到时为 {@code null}。 */
    private LocalDateTime publishedAt;

    /** 创建时间。 */
    private final LocalDateTime createdAt;

    /** 最近变更时间（唯一变更是指纹写入）。 */
    private LocalDateTime updatedAt;

    /** 私有构造：创建入口只有 {@link #of} 与 {@link #restore}。 */
    private Workspace(Long id, SourceType sourceType, SourceKey sourceKey, String sourceUri, String canonicalUrl,
                      Integer durationMs, LocalDateTime publishedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.sourceType = sourceType;
        this.sourceKey = sourceKey;
        this.sourceUri = sourceUri;
        this.canonicalUrl = canonicalUrl;
        this.durationMs = durationMs;
        this.publishedAt = publishedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 新建一份媒体身份：两列内容指纹都为空（首跑就是这样——指纹要等探测阶段才拿得到）。
     *
     * @param sourceType   来源类型
     * @param sourceKey    身份键
     * @param sourceUri    来源（URL 原文或上传文件原始名）
     * @param canonicalUrl 规范化后的 URL（可空）
     * @return 指纹两列为 {@code null} 的新身份（id 未落库，为 {@code null}）
     */
    public static Workspace of(SourceType sourceType, SourceKey sourceKey, String sourceUri, String canonicalUrl) {
        LocalDateTime now = LocalDateTime.now();
        return new Workspace(null, sourceType, sourceKey, sourceUri, canonicalUrl, null, null, now, now);
    }

    /**
     * 重建已落库的身份：仅供 persistence 层把数据库行还原成领域对象使用，业务代码不得用它凭空造身份。
     */
    public static Workspace restore(Long id, SourceType sourceType, SourceKey sourceKey, String sourceUri,
                                    String canonicalUrl, Integer durationMs, LocalDateTime publishedAt,
                                    LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new Workspace(id, sourceType, sourceKey, sourceUri, canonicalUrl, durationMs, publishedAt,
                createdAt, updatedAt);
    }

    /**
     * 写入内容指纹（指纹探测阶段调用）：两列一起更新，同时刷 updatedAt。
     * 首跑时两列从 {@code null} 写入也走这里；取不到指纹时调用方跳过本方法。
     *
     * @param durationMs  媒体时长（毫秒）；取不到时为 {@code null}
     * @param publishedAt 发布时间；取不到时为 {@code null}
     */
    public void updateFingerprint(Integer durationMs, LocalDateTime publishedAt) {
        this.durationMs = durationMs;
        this.publishedAt = publishedAt;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 两列都空 = 首次探测：只写指纹、不作废旧产物。
     *
     * <p>首跑没有旧产物可作废；如果只空一列，说明库里的指纹状态不完整，那不算首跑
     * （由 {@link #matchesFingerprint} 判成「变了」，方向往安全靠）。
     *
     * @return 两列都为 {@code null} 时返回 {@code true}
     */
    public boolean hasUnknownFingerprint() {
        return durationMs == null && publishedAt == null;
    }

    /**
     * 两列都非空、且与新探测的指纹逐列相等，才算「内容没变」。
     *
     * <p>只空一列时返回 {@code false}——比不全就当变了，安全方向是宁可多作废一次。
     *
     * @param fingerprint 新探测到的内容指纹；不可为 {@code null}
     * @return 两列都有值且都相等时返回 {@code true}
     */
    public boolean matchesFingerprint(ContentFingerprint fingerprint) {
        return durationMs != null && publishedAt != null
                && durationMs == fingerprint.durationMs()
                && publishedAt.equals(fingerprint.publishedAt());
    }
}
