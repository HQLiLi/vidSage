package com.vidsage.shared.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vidsage.domain.shared.ProgressListener;
import com.vidsage.domain.shared.StageEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ProgressMapper} 的纯单元测试：钉住区间映射公式与字段透传
 * （上提自 SubtitlePreparationStage 的私有 remap，公式行为不变）。
 */
class ProgressMapperTest {

    @Test
    void mapsFullRangeBoundsIntoTargetInterval() {
        List<StageEvent> received = new ArrayList<>();
        ProgressListener mapper = ProgressMapper.remap(received::add, 0, 60);

        mapper.onEvent(new StageEvent("acquire", 0, "开始", StageEvent.StageState.RUNNING));
        mapper.onEvent(new StageEvent("acquire", 50, "一半", StageEvent.StageState.RUNNING));
        mapper.onEvent(new StageEvent("extract-audio", 100, "完成", StageEvent.StageState.SUCCEEDED));

        assertEquals(0, received.get(0).progress());
        assertEquals(30, received.get(1).progress(), "50% 落在 0..60 的中点");
        assertEquals(60, received.get(2).progress(), "100% 应压到区间终点");
    }

    @Test
    void mapsIntoSubrangeStartingAtSixty() {
        List<StageEvent> received = new ArrayList<>();
        ProgressListener mapper = ProgressMapper.remap(received::add, 60, 80);

        mapper.onEvent(new StageEvent("clean", 0, "开始", StageEvent.StageState.RUNNING));
        mapper.onEvent(new StageEvent("clean", 50, "一半", StageEvent.StageState.RUNNING));
        mapper.onEvent(new StageEvent("clean", 100, "完成", StageEvent.StageState.SUCCEEDED));

        assertEquals(60, received.get(0).progress());
        assertEquals(70, received.get(1).progress());
        assertEquals(80, received.get(2).progress());
    }

    @Test
    void passesStageMessageAndStateThroughUntouched() {
        List<StageEvent> received = new ArrayList<>();
        ProgressListener mapper = ProgressMapper.remap(received::add, 0, 60);

        mapper.onEvent(new StageEvent("transcribe", 70, "已有 segments 产物，跳过转写", StageEvent.StageState.SKIPPED));

        StageEvent event = received.getFirst();
        assertEquals("transcribe", event.stage());
        assertEquals("已有 segments 产物，跳过转写", event.message());
        assertEquals(StageEvent.StageState.SKIPPED, event.state());
    }
}
