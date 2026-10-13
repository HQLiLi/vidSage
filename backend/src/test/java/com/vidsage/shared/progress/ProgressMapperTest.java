package com.vidsage.shared.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ProgressMapper} 的纯单元测试：钉住区间映射公式与字段透传
 * （上提自 SubtitlePreparationCoordinator 的私有 remap，公式行为不变）。
 */
class ProgressMapperTest {

    @Test
    void mapsFullRangeBoundsIntoTargetInterval() {
        List<StageProgress> received = new ArrayList<>();
        ProgressListener mapper = ProgressMapper.remap(received::add, 0, 60);

        mapper.onProgress(new StageProgress(SubtitleStageName.ACQUIRE_MEDIA.getStageName(), 0, "开始", StageProgress.State.RUNNING));
        mapper.onProgress(new StageProgress(SubtitleStageName.ACQUIRE_MEDIA.getStageName(), 50, "一半", StageProgress.State.RUNNING));
        mapper.onProgress(new StageProgress(SubtitleStageName.EXTRACT_AUDIO.getStageName(), 100, "完成", StageProgress.State.SUCCEEDED));

        assertEquals(0, received.get(0).progress());
        assertEquals(30, received.get(1).progress(), "50% 落在 0..60 的中点");
        assertEquals(60, received.get(2).progress(), "100% 应压到区间终点");
    }

    @Test
    void mapsIntoSubrangeStartingAtSixty() {
        List<StageProgress> received = new ArrayList<>();
        ProgressListener mapper = ProgressMapper.remap(received::add, 60, 80);

        mapper.onProgress(new StageProgress(SubtitleStageName.CLEAN_SUBTITLES.getStageName(), 0, "开始", StageProgress.State.RUNNING));
        mapper.onProgress(new StageProgress(SubtitleStageName.CLEAN_SUBTITLES.getStageName(), 50, "一半", StageProgress.State.RUNNING));
        mapper.onProgress(new StageProgress(SubtitleStageName.CLEAN_SUBTITLES.getStageName(), 100, "完成", StageProgress.State.SUCCEEDED));

        assertEquals(60, received.get(0).progress());
        assertEquals(70, received.get(1).progress());
        assertEquals(80, received.get(2).progress());
    }

    @Test
    void passesStageMessageAndStateThroughUntouched() {
        List<StageProgress> received = new ArrayList<>();
        ProgressListener mapper = ProgressMapper.remap(received::add, 0, 60);

        mapper.onProgress(new StageProgress(SubtitleStageName.TRANSCRIBE_AUDIO.getStageName(), 70, "已有 segments 产物，跳过转写", StageProgress.State.SKIPPED));

        StageProgress event = received.getFirst();
        assertEquals(SubtitleStageName.TRANSCRIBE_AUDIO.getStageName(), event.stage());
        assertEquals("已有 segments 产物，跳过转写", event.message());
        assertEquals(StageProgress.State.SKIPPED, event.state());
    }
}
