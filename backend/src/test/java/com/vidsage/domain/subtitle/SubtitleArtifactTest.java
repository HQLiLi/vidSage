package com.vidsage.domain.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vidsage.domain.subtitle.model.ArtifactFileKind;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@link SubtitleArtifact} 的纯单元测试：不起容器。
 *
 * <p>本类只承载两件事：{@code CREATING}（全 NULL 占位）出生、{@code CREATING} → {@code READY}
 * 换版落定（写文件列 + 哈希列），非法迁移抛 {@link IllegalStateException}。
 * {@code SUPERSEDED} 往后与 {@code FAILED} 一样是仓储上盲批量 UPDATE 的活，实体没有对应方法——
 * 需要那些状态的行时用 {@code restore} 造。
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

    // ---------- 非法迁移拒绝 ----------

    @Test
    void throwsWhenCompletingCreationFromNonCreatingStates() {
        SubtitleArtifact ready = ready();
        SubtitleArtifact superseded = superseded();

        assertThrows(IllegalStateException.class,
                () -> ready.completeCreation("a", "b", null, null, "1", "2", null, null));
        assertThrows(IllegalStateException.class,
                () -> superseded.completeCreation("a", "b", null, null, "1", "2", null, null));
    }

    // ---------- 工厂 ----------

    private static SubtitleArtifact creating() {
        return SubtitleArtifact.createCreatingArtifact(7L, 3L, ArtifactType.CLEANED, "", 5L, "fp-1");
    }

    /**
     * 造一条 SUPERSEDED 的行。走 {@code restore} 而不是"迁过去"——生产里没有迁移入口，
     * 这条状态由仓储的批量 UPDATE 写出来。
     */
    private static SubtitleArtifact superseded() {
        return SubtitleArtifact.restore(null, 7L, 3L, ArtifactType.CLEANED, "", 1,
                SubtitleArtifactStatus.SUPERSEDED, 5L, "fp-1",
                "segments.json", "subtitles.srt", "context.json", null,
                "aaa", "bbb", "ccc", null, LocalDateTime.of(2026, 10, 20, 10, 0));
    }

    private static SubtitleArtifact ready() {
        SubtitleArtifact artifact = creating();
        artifact.completeCreation("segments.json", "subtitles.srt", "context.json", null,
                "aaa", "bbb", "ccc", null);
        return artifact;
    }
}
