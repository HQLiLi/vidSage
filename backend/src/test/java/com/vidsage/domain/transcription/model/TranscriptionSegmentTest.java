package com.vidsage.domain.transcription.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link TranscriptionSegment} 的 id 规则：按时间轴合成、旧 JSON 缺字段时补上、写出时带上。
 *
 * <p>直接读写 JSON 而不起 Spring 容器：这段行为全靠 Jackson 反序列化的默认路径（走 record 的
 * 紧凑构造器），换个容器配置就可能不成立，所以要拿真的 {@code ObjectMapper} 验。
 */
class TranscriptionSegmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void derivesIdFromTimeWindow() {
        TranscriptionSegment segment = new TranscriptionSegment(0, 3520, "今天天气不错");

        assertEquals("seg-0-3520", segment.id(), "id 的形状固定为 seg-<beginTimeMs>-<endTimeMs>");
    }

    @Test
    void keepsExplicitId() {
        TranscriptionSegment segment = new TranscriptionSegment(0, 3520, "今天天气不错", "seg-custom");

        assertEquals("seg-custom", segment.id(), "显式给的 id 不能被覆盖");
    }

    @Test
    void derivesIdWhenGivenBlank() {
        assertEquals("seg-100-200", new TranscriptionSegment(100, 200, "x", "   ").id());
        assertEquals("seg-100-200", new TranscriptionSegment(100, 200, "x", null).id());
    }

    @Test
    void writesIdIntoJson() {
        String json = JSON.writeValueAsString(new TranscriptionSegment(0, 1440, "欢迎使用阿里云。"));

        assertTrue(json.contains("\"id\":\"seg-0-1440\""), "新产物落盘时该带 id，实际：" + json);
    }

    @Test
    void derivesIdWhenDeserializingLegacySegmentWithoutId() {
        TranscriptionSegment segment = JSON.readValue(
                "{\"beginTimeMs\":0,\"endTimeMs\":1440,\"text\":\"欢迎使用阿里云。\"}",
                TranscriptionSegment.class);

        assertEquals("seg-0-1440", segment.id(), "旧 JSON 没有 id 字段，读出来要按时间戳补上");
        assertEquals("欢迎使用阿里云。", segment.text());
    }

    @Test
    void derivesIdsWhenDeserializingLegacyTrackWithoutIds() {
        // 真实的旧产物形状：segments.json 是一层 SubtitleTrack，里面才是段落数组
        SubtitleTrack track = JSON.readValue("""
                {
                  "language": "zh",
                  "segments": [
                    {"beginTimeMs": 0, "endTimeMs": 1440, "text": "欢迎使用阿里云。"},
                    {"beginTimeMs": 1440, "endTimeMs": 3000, "text": "今天天气不错。"}
                  ],
                  "keywordsFingerprint": null
                }
                """, SubtitleTrack.class);

        List<TranscriptionSegment> segments = track.segments();
        assertEquals(List.of("seg-0-1440", "seg-1440-3000"),
                segments.stream().map(TranscriptionSegment::id).toList(),
                "每一句都该按自己的时间窗补出 id");
    }

    @Test
    void roundTripsIdThroughJson() {
        TranscriptionSegment original = new TranscriptionSegment(0, 1440, "你好");
        TranscriptionSegment read = JSON.readValue(JSON.writeValueAsString(original), TranscriptionSegment.class);

        assertEquals(original, read);
    }

    @Test
    void doesNotEmitNullIdWhenReadingLegacyJsonAndWritingBack() {
        TranscriptionSegment read = JSON.readValue(
                "{\"beginTimeMs\":0,\"endTimeMs\":1440,\"text\":\"你好\"}", TranscriptionSegment.class);
        String rewritten = JSON.writeValueAsString(read);

        assertFalse(rewritten.contains("\"id\":null"), "补出来的 id 该是字符串，不是 null");
        assertTrue(rewritten.contains("\"id\":\"seg-0-1440\""));
    }
}
