package com.vidsage.domain.subtitle.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.subtitle.model.common.SubtitleArtifactSpec;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link ArtifactFileKind} 的纯单元测试：两套名字、类型归属与文件名模板映射。
 */
class ArtifactFileKindTest {

    @Test
    void tokenRoundTripsEachKind() {
        for (ArtifactFileKind kind : ArtifactFileKind.values()) {
            assertEquals(Optional.of(kind), ArtifactFileKind.fromToken(kind.token()));
        }
    }

    @Test
    void fromTokenReturnsEmptyForUnknownToken() {
        assertTrue(ArtifactFileKind.fromToken("original-segments.json").isEmpty());
        assertTrue(ArtifactFileKind.fromToken(null).isEmpty());
    }

    @Test
    void keyAndTokenAreSeparateNames() {
        assertEquals("segments", ArtifactFileKind.SEGMENTS.key());
        assertEquals("segments.json", ArtifactFileKind.SEGMENTS.token());
        assertEquals("subtitles", ArtifactFileKind.SUBTITLES.key());
        assertEquals("context", ArtifactFileKind.CONTEXT.key());
        assertEquals("terms", ArtifactFileKind.TERMS.key());
    }

    @Test
    void belongsToCoversEveryTypeAndKindCombination() {
        assertTrue(ArtifactFileKind.SEGMENTS.belongsTo(ArtifactType.ORIGINAL));
        assertTrue(ArtifactFileKind.SUBTITLES.belongsTo(ArtifactType.ORIGINAL));
        assertFalse(ArtifactFileKind.CONTEXT.belongsTo(ArtifactType.ORIGINAL));
        assertFalse(ArtifactFileKind.TERMS.belongsTo(ArtifactType.ORIGINAL));

        assertTrue(ArtifactFileKind.SEGMENTS.belongsTo(ArtifactType.CLEANED));
        assertTrue(ArtifactFileKind.SUBTITLES.belongsTo(ArtifactType.CLEANED));
        assertTrue(ArtifactFileKind.CONTEXT.belongsTo(ArtifactType.CLEANED));
        assertFalse(ArtifactFileKind.TERMS.belongsTo(ArtifactType.CLEANED));

        assertTrue(ArtifactFileKind.SEGMENTS.belongsTo(ArtifactType.TRANSLATED));
        assertTrue(ArtifactFileKind.SUBTITLES.belongsTo(ArtifactType.TRANSLATED));
        assertFalse(ArtifactFileKind.CONTEXT.belongsTo(ArtifactType.TRANSLATED));
        assertTrue(ArtifactFileKind.TERMS.belongsTo(ArtifactType.TRANSLATED));
    }

    @Test
    void isRequiredForMarksEveryTypeAndKindCombination() {
        assertTrue(ArtifactFileKind.SEGMENTS.isRequiredFor(ArtifactType.ORIGINAL));
        assertTrue(ArtifactFileKind.SUBTITLES.isRequiredFor(ArtifactType.ORIGINAL));
        assertFalse(ArtifactFileKind.CONTEXT.isRequiredFor(ArtifactType.ORIGINAL));
        assertFalse(ArtifactFileKind.TERMS.isRequiredFor(ArtifactType.ORIGINAL));

        assertTrue(ArtifactFileKind.SEGMENTS.isRequiredFor(ArtifactType.CLEANED));
        assertTrue(ArtifactFileKind.SUBTITLES.isRequiredFor(ArtifactType.CLEANED));
        assertTrue(ArtifactFileKind.CONTEXT.isRequiredFor(ArtifactType.CLEANED));
        assertFalse(ArtifactFileKind.TERMS.isRequiredFor(ArtifactType.CLEANED));

        assertTrue(ArtifactFileKind.SEGMENTS.isRequiredFor(ArtifactType.TRANSLATED));
        assertTrue(ArtifactFileKind.SUBTITLES.isRequiredFor(ArtifactType.TRANSLATED));
        assertFalse(ArtifactFileKind.CONTEXT.isRequiredFor(ArtifactType.TRANSLATED));
        assertFalse(ArtifactFileKind.TERMS.isRequiredFor(ArtifactType.TRANSLATED),
                "术语查证是增益不是前提：同语种 / 无术语 / 查证未启用 / 全部失败时都没有这个文件");
    }

    @Test
    void specForMapsEachTypeToItsOwnFileTemplate() {
        assertEquals(SubtitleArtifactSpec.ORIGINAL_SEGMENTS,
                ArtifactFileKind.SEGMENTS.specFor(ArtifactType.ORIGINAL));
        assertEquals(SubtitleArtifactSpec.CLEAN_SEGMENTS,
                ArtifactFileKind.SEGMENTS.specFor(ArtifactType.CLEANED));
        assertEquals(SubtitleArtifactSpec.TRANSLATED_SEGMENTS,
                ArtifactFileKind.SEGMENTS.specFor(ArtifactType.TRANSLATED));

        assertEquals(SubtitleArtifactSpec.ORIGINAL_SRT,
                ArtifactFileKind.SUBTITLES.specFor(ArtifactType.ORIGINAL));
        assertEquals(SubtitleArtifactSpec.CLEAN_SRT,
                ArtifactFileKind.SUBTITLES.specFor(ArtifactType.CLEANED));
        assertEquals(SubtitleArtifactSpec.TRANSLATED_SRT,
                ArtifactFileKind.SUBTITLES.specFor(ArtifactType.TRANSLATED));

        assertEquals(SubtitleArtifactSpec.CONTEXT, ArtifactFileKind.CONTEXT.specFor(ArtifactType.CLEANED));
        assertEquals(SubtitleArtifactSpec.TERMS, ArtifactFileKind.TERMS.specFor(ArtifactType.TRANSLATED));
    }

    @Test
    void specForRejectsKindsTheTypeDoesNotCarry() {
        assertThrows(IllegalStateException.class,
                () -> ArtifactFileKind.CONTEXT.specFor(ArtifactType.ORIGINAL));
        assertThrows(IllegalStateException.class,
                () -> ArtifactFileKind.CONTEXT.specFor(ArtifactType.TRANSLATED));
        assertThrows(IllegalStateException.class,
                () -> ArtifactFileKind.TERMS.specFor(ArtifactType.ORIGINAL));
        assertThrows(IllegalStateException.class,
                () -> ArtifactFileKind.TERMS.specFor(ArtifactType.CLEANED));
    }

    @Test
    void resolvedFileNamesFollowTheTypeAndLanguage() {
        assertEquals("original-segments.json",
                ArtifactFileKind.SEGMENTS.specFor(ArtifactType.ORIGINAL).getFileName(""));
        assertEquals("clean-subtitles.srt",
                ArtifactFileKind.SUBTITLES.specFor(ArtifactType.CLEANED).getFileName(""));
        assertEquals("translated-segments-zh-hans.json",
                ArtifactFileKind.SEGMENTS.specFor(ArtifactType.TRANSLATED).getFileName("zh-Hans"));
        assertEquals("terms-zh-hans.json",
                ArtifactFileKind.TERMS.specFor(ArtifactType.TRANSLATED).getFileName("zh-Hans"));
    }
}
