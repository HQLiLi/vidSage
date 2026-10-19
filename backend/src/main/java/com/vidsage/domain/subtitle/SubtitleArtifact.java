package com.vidsage.domain.subtitle;

import com.vidsage.domain.subtitle.model.ArtifactFileKind;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 字幕产物版本：一行 = 一个逻辑版本，文件用固定四列表达。
 *
 * <p>状态机每个值恰好一条路径：{@code CREATING}（工厂出生，<b>该行永远全 NULL</b>）→
 * {@code READY}（{@link #completeCreation}，写文件列 + 哈希列 + 置 READY 是同一条 UPDATE）→
 * {@code SUPERSEDED}（{@link #supersede}）→ {@code DELETED}（{@link #confirmDeletion}，文件确认回收之后）。
 * {@code FAILED} 只有收口 / 启动收口两条盲批量 UPDATE 路径，<b>实体不承载</b>。
 *
 * <p>三代文件组合：{@code ORIGINAL} = segments + subtitles；{@code CLEANED} = 上面两个 + context；
 * {@code TRANSLATED(lang)} = segments + subtitles + terms。
 *
 * <p>{@code lang} 用空串（不是 {@code null}）表示「无语言维度」——MySQL 里 NULL 不参与唯一约束，
 * 允许 NULL 会让 {@code (task,type,revision,NULL)} 插出两条。{@code revision} 由数据库按 {@code MAX+1}
 * 生成（persistence 层回填），落库前为 {@code null}。
 */
@Getter
public class SubtitleArtifact {

    /** 数据库内部行 id；insert 回填，新建时在落库前为 {@code null}。 */
    private Long id;

    /** 所属字幕任务（逻辑外键 → subtitle_task.id）。 */
    private final long taskId;

    /** 产出它的那条 run（审计；收口靠它定位本次 run 插的占位行）。 */
    private final long derivedFromRunId;

    /** 产物类型（ORIGINAL / CLEANED / TRANSLATED）。 */
    private final ArtifactType artifactType;

    /** 语种；空串 = 无语言维度（只有 TRANSLATED 填真语种）。 */
    private final String lang;

    /** 同 (task,type,lang) 递增的版本号（MAX+1 生成，persistence 层回填）；落库前为 {@code null}。 */
    private Integer revision;

    /** 产物状态。 */
    private SubtitleArtifactStatus status;

    /** 审计 / 溯源：内容继承自哪一版上游；不参与级联、不参与读取判据，可空。 */
    private final Long derivedFromArtifactId;

    /** 关键词指纹；只有 CLEANED 有值，可空。 */
    private final String keywordsFingerprint;

    /** 检查点 JSON 文件名（segments）；CREATING 期间为 {@code null}。 */
    private String segmentsFileName;

    /** SRT 派生视图文件名（subtitles）；CREATING 期间为 {@code null}。 */
    private String subtitlesFileName;

    /** 摘要副产物文件名（context）；只有 CLEANED 有，可空。 */
    private String contextFileName;

    /** 术语查证副产物文件名（terms）；只有 TRANSLATED 有，可空。 */
    private String termsFileName;

    /** segments 成品文件的 sha256；与文件列同生同灭。 */
    private String segmentsSha256;

    /** subtitles 成品文件的 sha256；与文件列同生同灭。 */
    private String subtitlesSha256;

    /** context 成品文件的 sha256；与文件列同生同灭。 */
    private String contextSha256;

    /** terms 成品文件的 sha256；与文件列同生同灭。 */
    private String termsSha256;

    /** 创建时间。 */
    private final LocalDateTime createdAt;

    /**
     * 取某个文件位登记的文件名。
     *
     * @param kind 文件位
     * @return 该文件位的文件名；这一版没有这个文件位时为 {@code null}
     */
    public String getFileName(ArtifactFileKind kind) {
        return switch (kind) {
            case SEGMENTS -> segmentsFileName;
            case SUBTITLES -> subtitlesFileName;
            case CONTEXT -> contextFileName;
            case TERMS -> termsFileName;
        };
    }

    /** 私有构造：创建入口只有 {@link #createCreatingArtifact} 与 {@link #restore}。 */
    private SubtitleArtifact(Long id, long taskId, long derivedFromRunId, ArtifactType artifactType, String lang,
                             Integer revision, SubtitleArtifactStatus status, Long derivedFromArtifactId,
                             String keywordsFingerprint, String segmentsFileName, String subtitlesFileName,
                             String contextFileName, String termsFileName, String segmentsSha256,
                             String subtitlesSha256, String contextSha256, String termsSha256,
                             LocalDateTime createdAt) {
        this.id = id;
        this.taskId = taskId;
        this.derivedFromRunId = derivedFromRunId;
        this.artifactType = artifactType;
        // 字段不变量：无语言维度一律用空串，绝不落 null（NULL 不参与唯一约束，会插出重复版本）
        this.lang = lang == null ? "" : lang;
        this.revision = revision;
        this.status = status;
        this.derivedFromArtifactId = derivedFromArtifactId;
        this.keywordsFingerprint = keywordsFingerprint;
        this.segmentsFileName = segmentsFileName;
        this.subtitlesFileName = subtitlesFileName;
        this.contextFileName = contextFileName;
        this.termsFileName = termsFileName;
        this.segmentsSha256 = segmentsSha256;
        this.subtitlesSha256 = subtitlesSha256;
        this.contextSha256 = contextSha256;
        this.termsSha256 = termsSha256;
        this.createdAt = createdAt;
    }

    /**
     * 占位一条待产出的产物：状态 CREATING，<b>文件列与哈希列全 {@code null}</b>
     * （「CREATING 行永远全 NULL」，写文件列与置 READY 是同一刻的事）。
     *
     * @param taskId                所属字幕任务
     * @param derivedFromRunId      产出它的那条 run
     * @param artifactType          产物类型
     * @param lang                  语种；无语言维度传 {@code null} 或空串（一律落成空串）
     * @param derivedFromArtifactId 继承自哪一版上游；无则传 {@code null}
     * @param keywordsFingerprint   关键词指纹；只有 CLEANED 传值，其余传 {@code null}
     * @return 状态为 CREATING 的占位产物（id 与 revision 未落库，为 {@code null}）
     */
    public static SubtitleArtifact createCreatingArtifact(long taskId, long derivedFromRunId, ArtifactType artifactType,
                                              String lang, Long derivedFromArtifactId, String keywordsFingerprint) {
        return new SubtitleArtifact(null, taskId, derivedFromRunId, artifactType, lang, null,
                SubtitleArtifactStatus.CREATING, derivedFromArtifactId, keywordsFingerprint,
                null, null, null, null, null, null, null, null, LocalDateTime.now());
    }

    /**
     * 重建已落库的产物：仅供 persistence 层把数据库行还原成领域对象使用。接受任何状态
     * （含 FAILED），不做迁移校验——读的是已落库的事实，不是「迁过去」。
     */
    public static SubtitleArtifact restore(Long id, long taskId, long derivedFromRunId, ArtifactType artifactType,
                                           String lang, Integer revision, SubtitleArtifactStatus status,
                                           Long derivedFromArtifactId, String keywordsFingerprint,
                                           String segmentsFileName, String subtitlesFileName,
                                           String contextFileName, String termsFileName,
                                           String segmentsSha256, String subtitlesSha256,
                                           String contextSha256, String termsSha256, LocalDateTime createdAt) {
        return new SubtitleArtifact(id, taskId, derivedFromRunId, artifactType, lang, revision, status,
                derivedFromArtifactId, keywordsFingerprint, segmentsFileName, subtitlesFileName, contextFileName,
                termsFileName, segmentsSha256, subtitlesSha256, contextSha256, termsSha256, createdAt);
    }

    /**
     * CREATING → READY：换版落定，写四列文件列 + 四列哈希列并置 READY。
     * 文件列与哈希列必须成对传入（「哈希跟着文件列同生同灭」）；某类文件没有时传
     * {@code null}（如 ORIGINAL 没有 context / terms）。
     *
     * @param segmentsFileName  segments 文件名
     * @param subtitlesFileName subtitles 文件名
     * @param contextFileName   context 文件名（只有 CLEANED 有，其余 {@code null}）
     * @param termsFileName     terms 文件名（只有 TRANSLATED 有，其余 {@code null}）
     * @param segmentsSha256    segments 的 sha256
     * @param subtitlesSha256   subtitles 的 sha256
     * @param contextSha256     context 的 sha256（没有 context 时 {@code null}）
     * @param termsSha256       terms 的 sha256（没有 terms 时 {@code null}）
     * @throws IllegalStateException 当前不是 CREATING
     */
    public void completeCreation(String segmentsFileName, String subtitlesFileName, String contextFileName,
                          String termsFileName, String segmentsSha256, String subtitlesSha256,
                          String contextSha256, String termsSha256) {
        validateStatus(SubtitleArtifactStatus.CREATING, "READY（只有 CREATING 的占位行可以落定为 READY）");
        this.segmentsFileName = segmentsFileName;
        this.subtitlesFileName = subtitlesFileName;
        this.contextFileName = contextFileName;
        this.termsFileName = termsFileName;
        this.segmentsSha256 = segmentsSha256;
        this.subtitlesSha256 = subtitlesSha256;
        this.contextSha256 = contextSha256;
        this.termsSha256 = termsSha256;
        status = SubtitleArtifactStatus.READY;
    }

    /**
     * READY → SUPERSEDED：不再是当前版（同 (task,type,lang) 换版，或上游 CLEANED 换版的下游级联）。
     * <b>文件列留着</b>——下游译文是孤儿型，删文件时要用它的路径；覆盖型在换版时清空文件列
     * 是 DB 侧的事（{@code markSupersededAndClearFiles}），不在此建模。
     *
     * @throws IllegalStateException 当前不是 READY
     */
    public void supersede() {
        validateStatus(SubtitleArtifactStatus.READY, "SUPERSEDED（只有 READY 的产物会被取代）");
        status = SubtitleArtifactStatus.SUPERSEDED;
    }

    /**
     * SUPERSEDED → DELETED：文件已确认回收之后标（两阶段第二步）。
     *
     * @throws IllegalStateException 当前不是 SUPERSEDED
     */
    public void confirmDeletion() {
        validateStatus(SubtitleArtifactStatus.SUPERSEDED, "DELETED（只有 SUPERSEDED 的产物可以标记已回收）");
        status = SubtitleArtifactStatus.DELETED;
    }

    /** 状态守卫：当前状态与迁移目标不符即拒绝，消息给出「当前状态 → 目标状态」与合法前提。 */
    private void validateStatus(SubtitleArtifactStatus required, String targetDescription) {
        if (status != required) {
            throw new IllegalStateException("非法状态迁移：" + status + " → " + targetDescription
                    + "，当前状态为 " + status);
        }
    }
}
