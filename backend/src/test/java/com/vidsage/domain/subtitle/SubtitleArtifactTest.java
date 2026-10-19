package com.vidsage.domain.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.subtitle.model.ArtifactFileKind;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@link SubtitleArtifact} 状态机的纯单元测试：不起容器。
 *
 * <p>逐值覆盖：{@code CREATING}（全 NULL 占位）→ {@code READY}（写文件列 + 哈希列）
 * → {@code SUPERSEDED}（文件列留着）→ {@code DELETED}；非法迁移抛 {@link IllegalStateException}。
 * {@code FAILED} 只有收口 / 启动收口两条盲批量 UPDATE 路径，实体没有对应方法——用 {@code restore} 验证。
 */
class SubtitleArtifactTest {

    // ---------- 出生状态 ----------

    @Test
    void createCreatingArtifactStartsAllNullWithEmptyLangDefault() {
        SubtitleArtifact artifact = SubtitleArtifact.createCreatingArtifact(7L, 3L, ArtifactType.CLEANED, null, 5L, "fp-1");

        assertEquals(SubtitleArtifactStatus.CREATING, artifact.getStatus());
        assertEquals(7L, artifact.getTaskId());
        assertEquals(3L, artifact.getDerivedFromRunId());
        assertEquals(ArtifactType.CLEANED, artifact.getArtifactType());
        assertEquals("", artifact.getLang(), "无语言维度用空串，不是 null");
        assertEquals(5L, artifact.getDerivedFromArtifactId());
        assertEquals("fp-1", artifact.getKeywordsFingerprint());
        assertNull(artifact.getId(), "未落库前 id 应为 null");
        assertNull(artifact.getRevision(), "revision 由数据库 MAX+1 生成，落库前应为 null");
        assertNull(artifact.getSegmentsFileName());
        assertNull(artifact.getSubtitlesFileName());
        assertNull(artifact.getContextFileName());
        assertNull(artifact.getTermsFileName());
        assertNull(artifact.getSegmentsSha256());
        assertNull(artifact.getSubtitlesSha256());
        assertNull(artifact.getContextSha256());
        assertNull(artifact.getTermsSha256());
    }

    @Test
    void restoreRebuildsAnyStatusIncludingFailed() {
        LocalDateTime created = LocalDateTime.of(2026, 10, 17, 10, 0);
        SubtitleArtifact artifact = SubtitleArtifact.restore(11L, 7L, 3L, ArtifactType.TRANSLATED, "zh-Hans", 2,
                SubtitleArtifactStatus.FAILED, 5L, null, null, null, null, null, null, null, null, null, created);

        assertEquals(11L, artifact.getId());
        assertEquals(SubtitleArtifactStatus.FAILED, artifact.getStatus(),
                "restore 接受任何状态——FAILED 的唯一写入者是收口，实体没有对应方法");
        assertEquals("zh-Hans", artifact.getLang());
        assertEquals(2, artifact.getRevision());
    }

    // ---------- 合法迁移 ----------

    @Test
    void completeCreationTransitionsCreatingToReadyAndWritesFileAndHashColumns() {
        SubtitleArtifact artifact = creating();

        artifact.completeCreation("segments.json", "subtitles.srt", "context.json", null,
                "aaa", "bbb", "ccc", null);

        assertEquals(SubtitleArtifactStatus.READY, artifact.getStatus());
        assertEquals("segments.json", artifact.getSegmentsFileName());
        assertEquals("subtitles.srt", artifact.getSubtitlesFileName());
        assertEquals("context.json", artifact.getContextFileName());
        assertNull(artifact.getTermsFileName(), "CLEANED 没有 terms 文件");
        assertEquals("aaa", artifact.getSegmentsSha256());
        assertEquals("bbb", artifact.getSubtitlesSha256());
        assertEquals("ccc", artifact.getContextSha256());
        assertNull(artifact.getTermsSha256());
    }

    @Test
    void fileNameAndHashAccessorsMapEachFileKindToItsOwnColumn() {
        SubtitleArtifact artifact = ready();

        assertEquals("segments.json", artifact.getFileName(ArtifactFileKind.SEGMENTS));
        assertEquals("subtitles.srt", artifact.getFileName(ArtifactFileKind.SUBTITLES));
        assertEquals("context.json", artifact.getFileName(ArtifactFileKind.CONTEXT));
        assertNull(artifact.getFileName(ArtifactFileKind.TERMS), "CLEANED 没有 terms 文件位");
        assertEquals("aaa", artifact.getSha256(ArtifactFileKind.SEGMENTS));
        assertEquals("bbb", artifact.getSha256(ArtifactFileKind.SUBTITLES));
        assertEquals("ccc", artifact.getSha256(ArtifactFileKind.CONTEXT));
        assertNull(artifact.getSha256(ArtifactFileKind.TERMS), "没有的文件位就没有哈希");
    }

    @Test
    void supersedeTransitionsReadyAndKeepsFileColumns() {
        SubtitleArtifact artifact = ready();

        artifact.supersede();

        assertEquals(SubtitleArtifactStatus.SUPERSEDED, artifact.getStatus());
        assertEquals("segments.json", artifact.getSegmentsFileName(),
                "SUPERSEDED 时文件列留着——下游译文是孤儿型，删文件时要用它的路径");
        assertEquals("aaa", artifact.getSegmentsSha256());
    }

    @Test
    void confirmDeletionTransitionsSupersededToDeleted() {
        SubtitleArtifact artifact = ready();
        artifact.supersede();

        artifact.confirmDeletion();

        assertEquals(SubtitleArtifactStatus.DELETED, artifact.getStatus());
    }

    // ---------- 非法迁移拒绝 ----------

    @Test
    void throwsWhenCompletingCreationFromNonCreatingStates() {
        SubtitleArtifact ready = ready();
        SubtitleArtifact superseded = ready();
        superseded.supersede();

        assertThrows(IllegalStateException.class,
                () -> ready.completeCreation("a", "b", null, null, "1", "2", null, null));
        assertThrows(IllegalStateException.class,
                () -> superseded.completeCreation("a", "b", null, null, "1", "2", null, null));
    }

    @Test
    void throwsWhenSupersedingFromNonReadyStates() {
        SubtitleArtifact creating = creating();
        SubtitleArtifact superseded = ready();
        superseded.supersede();

        IllegalStateException e = assertThrows(IllegalStateException.class, creating::supersede);
        assertTrue(e.getMessage().contains("非法状态迁移"), e.getMessage());
        assertThrows(IllegalStateException.class, superseded::supersede);
    }

    @Test
    void throwsWhenConfirmingDeletionFromNonSupersededStates() {
        SubtitleArtifact creating = creating();
        SubtitleArtifact ready = ready();
        SubtitleArtifact deleted = ready();
        deleted.supersede();
        deleted.confirmDeletion();

        assertThrows(IllegalStateException.class, creating::confirmDeletion);
        assertThrows(IllegalStateException.class, ready::confirmDeletion);
        assertThrows(IllegalStateException.class, deleted::confirmDeletion);
    }

    // ---------- 工厂 ----------

    private static SubtitleArtifact creating() {
        return SubtitleArtifact.createCreatingArtifact(7L, 3L, ArtifactType.CLEANED, "", 5L, "fp-1");
    }

    private static SubtitleArtifact ready() {
        SubtitleArtifact artifact = creating();
        artifact.completeCreation("segments.json", "subtitles.srt", "context.json", null,
                "aaa", "bbb", "ccc", null);
        return artifact;
    }
}
