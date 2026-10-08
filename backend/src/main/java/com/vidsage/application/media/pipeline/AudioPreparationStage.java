package com.vidsage.application.media.pipeline;

import com.vidsage.application.media.WorkspaceService;
import com.vidsage.domain.media.model.AcquiredMedia;
import com.vidsage.domain.media.model.AudioArtifact;
import com.vidsage.domain.media.model.AudioSpec;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.shared.model.StageEvent;
import com.vidsage.domain.shared.model.StageEvent.StageState;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.subtitle.policy.CheckpointPolicy;
import com.vidsage.domain.media.port.AudioExtractor;
import com.vidsage.shared.fs.ArtifactFiles;

import java.nio.file.Path;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 音频准备编排：把「一条 {@link MediaInput} + 一个 {@link AudioSpec}」变成一份音频产物。
 *
 * <p>两个阶段：获取媒体 → 抽取音频。每个阶段在开始 /
 * 成功 / 跳过 / 失败时各发一个 {@link StageEvent}；失败路径在发完 {@code FAILED}（消息取异常
 * {@code getMessage()}）后<b>原样上抛</b>，不吞异常。
 *
 * <p><b>断点复用</b>（产物存在且大小 &gt; 0 即跳过，0 字节视为不存在、必须重跑）：
 * <ul>
 *   <li>获取媒体阶段由 {@link MediaAcquisitionStage#reuseExisting} 判断——只有适配器知道
 *       {@code source.<ext>} 的推导规则与 info json 的解析；</li>
 *   <li>抽取音频阶段由本阶段判断——音频产物名由 {@link AudioArtifact#fileName} 按规格指纹给出
 *       （命名规则单源在 domain），且<b>先校验 spec 再判断点</b>，不放过「非法 spec + 文件恰好存在」
 *       这条路径。</li>
 * </ul>
 * 跳过时发 {@code SKIPPED} 事件并记 INFO 日志：<b>断点复用必须可观测</b>，否则「跳过了」与
 * 「没跑」分不出来。
 *
 * <p><b>音频产物名与进度权重都是过渡态</b>：真正的进度形状与阶段划分应由 MVP-1 的
 * 异步管线 / SSE payload 定锤，届时以那边为准。
 */
@Slf4j
@Component
public class AudioPreparationStage {


    /**
     * 进度权重（<b>过渡约定</b>，形状以 MVP-1 的 SSE payload 为准）：获取媒体 0→50，抽取音频 50→100。
     * SKIPPED 沿用该阶段本应到达的累计值，保证进度单调、不被跳过阶段回退。
     */
    private static final int ACQUIRE_START = 0;

    private static final int ACQUIRE_DONE = 50;

    private static final int EXTRACT_START = 50;

    private static final int EXTRACT_DONE = 100;

    private final WorkspaceService workspaceService;
    private final MediaAcquisitionStage acquisitionStage;
    private final AudioExtractor audioExtractor;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param workspaceService 工作目录解析
     * @param acquisitionStage 媒体获取分派（不感知任何适配器实现类）
     * @param audioExtractor   音频抽取（不感知 ffmpeg 实现类）
     */
    public AudioPreparationStage(WorkspaceService workspaceService,
                                 MediaAcquisitionStage acquisitionStage,
                                 AudioExtractor audioExtractor) {
        this.workspaceService = workspaceService;
        this.acquisitionStage = acquisitionStage;
        this.audioExtractor = audioExtractor;
    }

    /**
     * 准备音频，不关心进度（内部走 {@link ProgressListener#noop()}）。
     *
     * @param input 媒体输入
     * @param spec  音频输出规格
     * @return 音频产物
     */
    public AudioArtifact prepare(MediaInput input, AudioSpec spec) {
        return prepare(input, spec, ProgressListener.noop());
    }

    /**
     * 准备音频：获取媒体（必要时下载）后按规格抽取音频，两阶段均支持断点复用。
     * 断点复用：产物文件本身作为检查点的幂等重跑：同一个输入再次调用 prepare()时，
     * 某个阶段如果发现上一次跑出来的产物已经好好躺在磁盘上，就跳过这个阶段直接拿现成的，
     * 而不是重新下载 / 重新转码。
     *
     * @param input    媒体输入；不可为空
     * @param spec     音频输出规格；不可为空（字段合法性由其构造器保证，见 {@link AudioSpec}）
     * @param listener 阶段进度监听；<b>不可为 {@code null}</b>——不关心进度请改用两参重载
     *                 {@link #prepare(MediaInput, AudioSpec)}（内部走 {@link ProgressListener#noop()}）
     * @return 音频产物（路径 + 规格 + 字节数）
     * @throws IllegalArgumentException 输入或规格为空、{@code listener} 为 {@code null}，
     *                                  或输入内容不合法（文件不存在、路径非法等）
     * @throws IllegalStateException    外部工具缺失，或外部命令未按预期完成（非零退出、超时、产物为空）
     */
    public AudioArtifact prepare(MediaInput input, AudioSpec spec, ProgressListener listener) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        if (spec == null) {
            throw new IllegalArgumentException("音频规格不能为空：请传 AudioSpec（如 AudioSpec.WAV_PCM_16K_MONO）");
        }
        if (listener == null) {
            throw new IllegalArgumentException("进度监听不能为 null：请传一个 ProgressListener，"
                    + "或改用不带 listener 的 prepare(input, spec) 两参重载（内部走 ProgressListener.noop()）");
        }

        // 获取该媒体的产物目录
        Path workDir = workspaceService.workspaceFor(input);
        log.debug("开始准备音频: workDir={}, container={}", workDir.getFileName(), spec.container());

        AcquiredMedia media = runAcquireStage(input, workDir, listener);
        return runExtractStage(media, workDir, spec, listener);
    }

    /**
     * 获取媒体阶段：已有完整产物则跳过，否则委托获取。
     */
    private AcquiredMedia runAcquireStage(MediaInput input, Path workDir, ProgressListener listener) {
        try {
            Optional<AcquiredMedia> existing = acquisitionStage.reuseExisting(input, workDir);
            if (existing.isPresent()) {
                log.info("获取媒体阶段跳过（断点复用）: media={}", existing.get().mediaPath().getFileName());
                emit(listener, SubtitleStageName.ACQUIRE_MEDIA.getStageName(), ACQUIRE_DONE, "已有本地媒体，跳过获取", StageState.SKIPPED);
                return existing.get();
            }
            emit(listener, SubtitleStageName.ACQUIRE_MEDIA.getStageName(), ACQUIRE_START, "开始获取媒体", StageState.RUNNING);
            AcquiredMedia media = acquisitionStage.acquire(input, workDir);
            emit(listener, SubtitleStageName.ACQUIRE_MEDIA.getStageName(), ACQUIRE_DONE, "媒体获取完成", StageState.SUCCEEDED);
            return media;
        } catch (RuntimeException e) {
            emit(listener, SubtitleStageName.ACQUIRE_MEDIA.getStageName(), ACQUIRE_START, "获取媒体失败：" + describe(e), StageState.FAILED);
            throw e;
        }
    }

    /**
     * 抽取音频阶段：判是否有非空音频产物——有则跳过，否则委托抽取。
     */
    private AudioArtifact runExtractStage(AcquiredMedia media, Path workDir, AudioSpec spec,
                                          ProgressListener listener) {
        try {
            // 抽取目标按规格指纹命名（命名规则单源在 AudioArtifact.fileName）：
            // 同一输入、不同规格的产物互不覆盖，断点复用不会错拿
            Path audioPath = workDir.resolve(AudioArtifact.fileName(spec));
            long existingSize = ArtifactFiles.sizeOrZero(audioPath);
            // 断点判据语义单源在 CheckpointPolicy（非空 = 已完成、0 字节 = 未完成）：
            // 与三个字幕阶段的 JSON 检查点是同一条「产物非空才算完成」政策
            if (CheckpointPolicy.isCompleted(existingSize)) {
                log.info("extract 阶段跳过（断点复用）: file={}, sizeBytes={}",
                        audioPath.getFileName(), existingSize);

                emit(listener, SubtitleStageName.EXTRACT_AUDIO.getStageName(), EXTRACT_DONE, "已有音频产物，跳过抽取", StageState.SKIPPED);
                return new AudioArtifact(audioPath, spec, existingSize);
            }
            emit(listener, SubtitleStageName.EXTRACT_AUDIO.getStageName(), EXTRACT_START, "开始抽取音频", StageState.RUNNING);
            AudioArtifact artifact = audioExtractor.extract(media.mediaPath(), audioPath, spec);
            emit(listener, SubtitleStageName.EXTRACT_AUDIO.getStageName(), EXTRACT_DONE, "音频抽取完成", StageState.SUCCEEDED);
            return artifact;
        } catch (RuntimeException e) {
            emit(listener, SubtitleStageName.EXTRACT_AUDIO.getStageName(), EXTRACT_START, "抽取音频失败：" + describe(e), StageState.FAILED);
            throw e;
        }
    }

    /**
     * 异常消息：{@code getMessage()} 可能为 {@code null}，此时退回简单类名，避免事件消息里出现 "null"。
     */
    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * 发一个阶段事件。
     */
    private static void emit(ProgressListener listener, String stage, int progress, String message,
                             StageState state) {
        log.debug("阶段事件: stage={}, progress={}, state={}", stage, progress, state);
        listener.onEvent(new StageEvent(stage, progress, message, state));
    }
}
