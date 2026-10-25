package com.vidsage.application.subtitle;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.vidsage.application.subtitle.pipeline.SubtitleCleanupCoordinator;
import com.vidsage.application.subtitle.pipeline.SubtitleFingerprintCoordinator;
import com.vidsage.application.subtitle.pipeline.SubtitlePreparationCoordinator;
import com.vidsage.application.task.SubtitleMainService;
import com.vidsage.application.task.SubtitleRecleanService;
import java.lang.reflect.Constructor;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 读取口边界的防回归闸：<b>看机器产物的那条链路不许依赖纠错覆盖层</b>。
 *
 * <p>为什么单独一条测试：清洗复用判定、内容指纹、对账这些地方一旦有人「顺手」把覆盖层接进去，
 * 用户改了字幕就会被误判成「产物变了」，重跑、作废、清缓存会连锁发生——而这类错误在功能用例里
 * 通常不显形。仓库没有 ArchUnit，就用构造器签名做一条最简单的钉住：这些类要是哪天开始收
 * {@link SubtitleCorrectionOverlay} 或 {@link SubtitleTrackReader}，本用例当场变红，逼着改的人
 * 先想清楚「这是不是又一个用户可见的读取口」。
 *
 * <p>名单只收「以机器产物为准」的编排件；下载、纠错用例、翻译协调器本来就该依赖它们，不在名单里。
 */
class CorrectionReadPathBoundaryTest {

    /** 以机器产物为准的编排件：清洗、指纹、主管线、重新生成。 */
    private static final List<Class<?>> MACHINE_PRODUCT_READERS = List.of(
            SubtitlePreparationCoordinator.class,
            SubtitleCleanupCoordinator.class,
            SubtitleFingerprintCoordinator.class,
            SubtitleMainService.class,
            SubtitleRecleanService.class);

    @Test
    void machineProductReadersDoNotDependOnCorrections() {
        for (Class<?> type : MACHINE_PRODUCT_READERS) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    assertNotEquals(SubtitleCorrectionOverlay.class, parameter,
                            type.getSimpleName() + " 看的是机器产物，不能依赖纠错覆盖层："
                                    + "把纠错接进复用 / 指纹 / 对账会让用户一改字幕就触发重跑");
                    assertNotEquals(SubtitleTrackReader.class, parameter,
                            type.getSimpleName() + " 看的是机器产物，不能走「用户可见」的读取口："
                                    + "它读的是磁盘上的机器原文，不是叠加纠错后的内容");
                }
            }
        }
    }
}
