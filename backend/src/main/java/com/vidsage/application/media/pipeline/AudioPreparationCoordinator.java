package com.vidsage.application.media.pipeline;

import com.vidsage.domain.media.model.AcquiredMedia;
import com.vidsage.domain.media.model.AudioStageOutput;
import com.vidsage.domain.media.model.AudioSpec;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.media.port.AudioExtractor;
import com.vidsage.domain.shared.model.StageProgress.State;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.subtitle.policy.CheckpointPolicy;
import com.vidsage.shared.fs.ArtifactFileUtils;
import com.vidsage.shared.progress.StageEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 音频准备编排：把「一条 {@link MediaInput} + 一个 {@link AudioSpec}」变成一份音频产物。
 *
 * <p>两个阶段：获取媒体 → 抽取音频（发出的事件序列见 directory-structure.md「进度事件序列」）。
 * 失败路径在发完 {@code FAILED}（消息取异常 {@code getMessage()}）后 <b>原样上抛</b>，不吞异常。
 *
 * <p><b>断点复用</b>（产物存在且大小 &gt; 0 即跳过，0 字节视为不存在、必须重跑）：获取媒体阶段由
 * {@link MediaAcquirerDispatcher#reuseExisting} 判断——只有适配器知道 {@code source.<ext>} 的推导
 * 规则与 info json 的解析；抽取音频阶段由本阶段判断，音频产物名由 {@link AudioStageOutput#fileName}
 * 按规格指纹给出（命名规则单源在 domain），且<b>先校验 spec 再判断点</b>，不放过「非法 spec +
 * 文件恰好存在」这条路径。
 */
@Slf4j
@Component
public class AudioPreparationCoordinator {

    private final MediaAcquirerDispatcher acquisitionStage;
    private final AudioExtractor audioExtractor;

    /**
     * @param acquisitionStage 媒体获取分派（不感知任何适配器实现类）
     * @param audioExtractor   音频抽取（不感知 ffmpeg 实现类）
     */
    public AudioPreparationCoordinator(
            MediaAcquirerDispatcher acquisitionStage,
            AudioExtractor audioExtractor
    ) {
        this.acquisitionStage = acquisitionStage;
        this.audioExtractor = audioExtractor;
    }

    /**
     * 准备音频，不报告进度。
     *
     * @param input   媒体输入
     * @param workDir 这条输入的工作区目录（调用方已定位好）
     * @param spec    音频输出规格
     * @return 音频产物
     */
    public AudioStageOutput prepare(MediaInput input, Path workDir, AudioSpec spec) {
        return prepare(input, workDir, spec, ProgressListener.noop());
    }

    /**
     * 准备音频：获取媒体（必要时下载）后按规格抽取音频，两阶段均支持断点复用。
     *
     * @param input    媒体输入；不可为空
     * @param workDir  这条输入的工作区目录（调用方已定位好）；不可为空
     * @param spec     音频输出规格；不可为空（字段合法性由其构造器保证，见 {@link AudioSpec}）
     * @param listener 阶段进度监听；<b>不可为 {@code null}</b>——不关心进度请改用不带 listener 的重载
     *                 {@link #prepare(MediaInput, Path, AudioSpec)}
     * @return 音频产物（路径 + 规格 + 字节数）
     * @throws IllegalArgumentException 输入或规格为空、{@code listener} 为 {@code null}，
     *                                  或输入内容不合法（文件不存在、路径非法等）
     * @throws IllegalStateException    外部工具缺失，或外部命令未按预期完成（非零退出、超时、产物为空）
     */
    public AudioStageOutput prepare(MediaInput input, Path workDir, AudioSpec spec, ProgressListener listener) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        if (spec == null) {
            throw new IllegalArgumentException("音频规格不能为空：请传 AudioSpec（如 AudioSpec.WAV_PCM_16K_MONO）");
        }
        if (listener == null) {
            throw new IllegalArgumentException("进度监听不能为 null：请传一个 ProgressListener，"
                    + "或改用不带 listener 的 prepare(input, workDir, spec) 重载（内部走 ProgressListener.noop()）");
        }

        log.debug("开始准备音频: workDir={}, container={}", workDir.getFileName(), spec.container());

        AcquiredMedia media = runAcquireStage(input, workDir, listener);
        return runExtractStage(media, workDir, spec, listener);
    }

    /**
     * 获取媒体阶段：已有完整产物则跳过，否则委托获取。
     */
    private AcquiredMedia runAcquireStage(MediaInput input, Path workDir, ProgressListener listener) {

        String stageName = SubtitleStageName.ACQUIRE_MEDIA.getStageName();

        try {
            Optional<AcquiredMedia> existing = acquisitionStage.reuseExisting(input, workDir);
            if (existing.isPresent()) {
                log.info("获取媒体阶段跳过（断点复用）: media={}", existing.get().mediaPath().getFileName());
                StageEvents.emit(listener, stageName, SubtitleStageName.ACQUIRE_MEDIA.end(), "已有本地媒体，跳过获取", State.SKIPPED);
                return existing.get();
            }
            StageEvents.emit(listener, stageName, SubtitleStageName.ACQUIRE_MEDIA.start(), "开始获取媒体", State.RUNNING);
            AcquiredMedia media = acquisitionStage.acquire(input, workDir);
            StageEvents.emit(listener, stageName, SubtitleStageName.ACQUIRE_MEDIA.end(), "媒体获取完成", State.SUCCEEDED);
            return media;
        } catch (RuntimeException e) {
            StageEvents.emit(listener, stageName, SubtitleStageName.ACQUIRE_MEDIA.start(), "获取媒体失败：" + describe(e), State.FAILED);
            throw e;
        }
    }

    /**
     * 抽取音频阶段：判是否有非空音频产物——有则跳过，否则委托抽取。
     */
    private AudioStageOutput runExtractStage(AcquiredMedia media, Path workDir, AudioSpec spec,
                                          ProgressListener listener) {
        String stageName = SubtitleStageName.EXTRACT_AUDIO.getStageName();

        try {
            // 抽取目标按规格指纹命名（命名规则单源在 AudioStageOutput.fileName）：
            // 同一输入、不同规格的产物互不覆盖，断点复用不会错拿
            Path audioPath = workDir.resolve(AudioStageOutput.fileName(spec));
            long existingSize = ArtifactFileUtils.sizeOrZero(audioPath);
            // 断点判据语义单源在 CheckpointPolicy（非空 = 已完成、0 字节 = 未完成）：
            // 与三个字幕阶段的 JSON 检查点是同一条「产物非空才算完成」政策
            if (CheckpointPolicy.isCompleted(existingSize)) {
                log.info("extract 阶段跳过（断点复用）: file={}, sizeBytes={}",
                        audioPath.getFileName(), existingSize);

                StageEvents.emit(listener, stageName, SubtitleStageName.EXTRACT_AUDIO.end(), "已有音频产物，跳过抽取", State.SKIPPED);
                return new AudioStageOutput(audioPath, spec, existingSize);
            }
            StageEvents.emit(listener, stageName, SubtitleStageName.EXTRACT_AUDIO.start(), "开始抽取音频", State.RUNNING);
            AudioStageOutput artifact = audioExtractor.extract(media.mediaPath(), audioPath, spec);
            StageEvents.emit(listener, stageName, SubtitleStageName.EXTRACT_AUDIO.end(), "音频抽取完成", State.SUCCEEDED);
            return artifact;
        } catch (RuntimeException e) {
            StageEvents.emit(listener, stageName, SubtitleStageName.EXTRACT_AUDIO.start(), "抽取音频失败：" + describe(e), State.FAILED);
            throw e;
        }
    }

    /**
     * 异常消息：{@code getMessage()} 可能为 {@code null}，此时退回简单类名，避免事件消息里出现 "null"。
     */
    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

}
