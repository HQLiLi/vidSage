package com.vidsage.application.media.pipeline;

import com.vidsage.application.media.IntermediateFileOwner;
import com.vidsage.application.media.IntermediateFileStore;
import com.vidsage.domain.intermediate.model.IntermediateFileType;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 音频准备编排：把「一条 {@link MediaInput} + 一个 {@link AudioSpec}」变成一份音频产物。
 *
 * <p>两个阶段：获取媒体 → 抽取音频（发出的事件序列见 directory-structure.md「进度事件序列」）。
 * 失败路径在发完 {@code FAILED}（消息取异常 {@code getMessage()}）后 <b>原样上抛</b>，不吞异常。
 *
 * <p><b>断点复用判据是「表里 READY 且文件在」</b>：媒体源、元信息、音频三类文件都要先看
 * {@code intermediate_file} 里有没有 {@code READY} 行，再看工作区里文件在不在。只看文件会把已经
 * 作废的缓存当成有效缓存——内容变更级联只动数据库，不会去删干净每一份文件。
 *
 * <p><b>表拒绝复用就等于重新产出</b>：盘上残留的旧文件先尽力删掉，再交给获取 / 抽取；
 * yt-dlp 另有 {@code --force-overwrites} 兜底，抽取器则要求目标不存在。这样「拒绝复用」不会
 * 被底层工具理解成「已下载 / 已存在」而静默跳过。
 */
@Slf4j
@Component
public class AudioPreparationCoordinator {

    private final MediaAcquirerDispatcher acquisitionStage;
    private final AudioExtractor audioExtractor;
    private final IntermediateFileStore intermediateFileStore;

    /**
     * @param acquisitionStage    媒体获取分派（不感知任何适配器实现类）
     * @param audioExtractor      音频抽取（不感知 ffmpeg 实现类）
     * @param intermediateFileStore 中间产物的登记与复用判据
     */
    public AudioPreparationCoordinator(
            MediaAcquirerDispatcher acquisitionStage,
            AudioExtractor audioExtractor,
            IntermediateFileStore intermediateFileStore
    ) {
        this.acquisitionStage = acquisitionStage;
        this.audioExtractor = audioExtractor;
        this.intermediateFileStore = intermediateFileStore;
    }

    /**
     * 准备音频，不报告进度。
     *
     * @param input   媒体输入
     * @param workDir 这条输入的工作区目录（调用方已定位好）
     * @param spec    音频输出规格
     * @param owner   中间产物归属（哪个任务、哪条 run）
     * @return 音频产物
     */
    public AudioStageOutput prepare(MediaInput input, Path workDir, AudioSpec spec, IntermediateFileOwner owner) {
        return prepare(input, workDir, spec, ProgressListener.noop(), owner);
    }

    /**
     * 准备音频：获取媒体（必要时下载）后按规格抽取音频，两阶段均按「表 READY + 文件在」复用。
     *
     * @param input    媒体输入；不可为空
     * @param workDir  这条输入的工作区目录（调用方已定位好）；不可为空
     * @param spec     音频输出规格；不可为空（字段合法性由其构造器保证，见 {@link AudioSpec}）
     * @param listener 阶段进度监听；<b>不可为 {@code null}</b>——不关心进度请改用不带 listener 的重载
     * @param owner    中间产物归属；不可为空
     * @return 音频产物（路径 + 规格 + 字节数）
     * @throws IllegalArgumentException 输入 / 规格 / 归属为空，{@code listener} 为 {@code null}，
     *                                  或输入内容不合法（文件不存在、路径非法等）
     * @throws IllegalStateException    外部工具缺失、外部命令未按预期完成（非零退出、超时、产物为空），
     *                                  或中间产物登记失败
     */
    public AudioStageOutput prepare(MediaInput input, Path workDir, AudioSpec spec, ProgressListener listener,
                                    IntermediateFileOwner owner) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        if (spec == null) {
            throw new IllegalArgumentException("音频规格不能为空：请传 AudioSpec（如 AudioSpec.WAV_PCM_16K_MONO）");
        }
        if (listener == null) {
            throw new IllegalArgumentException("进度监听不能为 null：请传一个 ProgressListener，"
                    + "或改用不带 listener 的 prepare(input, workDir, spec, owner) 重载");
        }
        if (owner == null) {
            throw new IllegalArgumentException("中间产物归属不能为空：请传 IntermediateFileOwner(taskId, runId)");
        }

        log.debug("开始准备音频: workDir={}, container={}", workDir.getFileName(), spec.container());

        AcquiredMedia media = runAcquireStage(input, workDir, listener, owner);
        return runExtractStage(media, workDir, spec, listener, owner);
    }

    /**
     * 获取媒体阶段：表 READY 且文件在则跳过，否则清掉盘上残留再委托获取，最后登记产物。
     */
    private AcquiredMedia runAcquireStage(MediaInput input, Path workDir, ProgressListener listener,
                                          IntermediateFileOwner owner) {

        String stageName = SubtitleStageName.ACQUIRE_MEDIA.getStageName();

        try {
            Optional<AcquiredMedia> existing = acquisitionStage.reuseExisting(input, workDir);
            if (existing.isPresent() && isMediaReusable(owner, existing.get(), workDir)) {
                log.info("获取媒体阶段跳过（断点复用）: media={}", existing.get().mediaPath().getFileName());
                StageEvents.emit(listener, stageName, SubtitleStageName.ACQUIRE_MEDIA.end(), "已有本地媒体，跳过获取", State.SKIPPED);
                return existing.get();
            }
            // 盘上有旧文件但表说不能复用：先清掉，别让 yt-dlp 以为「已下载」而跳过
            existing.ifPresent(media -> deleteStaleMedia(media, workDir));
            StageEvents.emit(listener, stageName, SubtitleStageName.ACQUIRE_MEDIA.start(), "开始获取媒体", State.RUNNING);
            AcquiredMedia media = acquisitionStage.acquire(input, workDir);
            recordAcquiredMedia(owner, media, workDir);
            StageEvents.emit(listener, stageName, SubtitleStageName.ACQUIRE_MEDIA.end(), "媒体获取完成", State.SUCCEEDED);
            return media;
        } catch (RuntimeException e) {
            StageEvents.emit(listener, stageName, SubtitleStageName.ACQUIRE_MEDIA.start(), "获取媒体失败：" + describe(e), State.FAILED);
            throw e;
        }
    }

    /**
     * 抽取音频阶段：表 READY 且文件非空则跳过，否则清掉盘上残留再抽取，最后登记产物。
     */
    private AudioStageOutput runExtractStage(AcquiredMedia media, Path workDir, AudioSpec spec,
                                          ProgressListener listener, IntermediateFileOwner owner) {
        String stageName = SubtitleStageName.EXTRACT_AUDIO.getStageName();

        try {
            // 抽取目标按规格指纹命名（命名规则单源在 AudioStageOutput.fileName）：
            // 同一输入、不同规格的产物互不覆盖，断点复用不会错拿
            Path audioPath = workDir.resolve(AudioStageOutput.fileName(spec));
            long existingSize = ArtifactFileUtils.sizeOrZero(audioPath);
            // 断点判据语义单源在 CheckpointPolicy（非空 = 已完成、0 字节 = 未完成）：
            // 与三个字幕阶段的 JSON 检查点是同一条「产物非空才算完成」政策
            boolean fileComplete = CheckpointPolicy.isCompleted(existingSize);
            if (fileComplete
                    && intermediateFileStore.isReusable(owner.taskId(), audioPath.getFileName().toString(), workDir)) {
                log.info("extract 阶段跳过（断点复用）: file={}, sizeBytes={}",
                        audioPath.getFileName(), existingSize);

                StageEvents.emit(listener, stageName, SubtitleStageName.EXTRACT_AUDIO.end(), "已有音频产物，跳过抽取", State.SKIPPED);
                return new AudioStageOutput(audioPath, spec, existingSize);
            }
            if (Files.exists(audioPath)) {
                // 表拒绝复用（或 0 字节残骸）：抽取器拒绝覆盖已存在的产物，先删掉旧文件
                deleteStaleAudio(audioPath);
            }
            StageEvents.emit(listener, stageName, SubtitleStageName.EXTRACT_AUDIO.start(), "开始抽取音频", State.RUNNING);
            AudioStageOutput artifact = audioExtractor.extract(media.mediaPath(), audioPath, spec);
            intermediateFileStore.recordProduced(owner.taskId(), owner.runId(), IntermediateFileType.AUDIO,
                    workDir, artifact.audioPath());
            StageEvents.emit(listener, stageName, SubtitleStageName.EXTRACT_AUDIO.end(), "音频抽取完成", State.SUCCEEDED);
            return artifact;
        } catch (RuntimeException e) {
            StageEvents.emit(listener, stageName, SubtitleStageName.EXTRACT_AUDIO.start(), "抽取音频失败：" + describe(e), State.FAILED);
            throw e;
        }
    }

    /**
     * 媒体与元信息能不能复用：工作区外的文件没有登记可查，按文件判据放行；工作区内要求每个
     * 实际返回的文件位都「表 READY 且文件在」。
     */
    private boolean isMediaReusable(IntermediateFileOwner owner, AcquiredMedia media, Path workDir) {
        if (!isInsideWorkspace(workDir, media.mediaPath())) {
            return true;
        }
        if (!intermediateFileStore.isReusable(owner.taskId(), media.mediaPath().getFileName().toString(), workDir)) {
            return false;
        }
        return media.infoJsonPath()
                .map(info -> intermediateFileStore.isReusable(owner.taskId(), info.getFileName().toString(), workDir))
                .orElse(true);
    }

    /**
     * 登记刚落盘的媒体源与元信息（工作区外的文件由 store 自己跳过）。
     */
    private void recordAcquiredMedia(IntermediateFileOwner owner, AcquiredMedia media, Path workDir) {
        intermediateFileStore.recordProduced(owner.taskId(), owner.runId(), IntermediateFileType.SOURCE_MEDIA,
                workDir, media.mediaPath());
        media.infoJsonPath().ifPresent(info -> intermediateFileStore.recordProduced(owner.taskId(), owner.runId(),
                IntermediateFileType.INFO_JSON, workDir, info));
    }

    /**
     * 清掉不可复用的旧媒体与旧元信息：只动工作区内的文件，删不动只记 WARN（yt-dlp 的
     * {@code --force-overwrites} 会覆盖它）。
     */
    private static void deleteStaleMedia(AcquiredMedia media, Path workDir) {
        deleteStaleFile(media.mediaPath(), workDir);
        media.infoJsonPath().ifPresent(info -> deleteStaleFile(info, workDir));
    }

    /**
     * 清掉不可复用的旧音频：抽取器拒绝覆盖，删不动必须让本趟失败，不能拿旧音频冒充新产物。
     */
    private static void deleteStaleAudio(Path audioPath) {
        try {
            Files.deleteIfExists(audioPath);
        } catch (IOException e) {
            // 只带文件名与异常类名：异常消息可能带完整路径，而目录名内嵌身份键（脱敏红线）
            throw new IllegalStateException("旧音频产物删除失败（" + audioPath.getFileName()
                    + "）：请检查文件是否被其它进程占用；原因：" + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 尽力删一个工作区内的旧文件：删不动只记 WARN，不挡后面的重新获取。
     */
    private static void deleteStaleFile(Path file, Path workDir) {
        if (!isInsideWorkspace(workDir, file)) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("旧中间产物删除失败（获取阶段会覆盖它）: file={}, reason={}",
                    file.getFileName(), e.getClass().getSimpleName());
        }
    }

    /**
     * 文件是不是在工作区目录之内（用绝对规范路径比，避免相对路径 / {@code ..} 造成误判）。
     */
    private static boolean isInsideWorkspace(Path workDir, Path file) {
        return file.toAbsolutePath().normalize().startsWith(workDir.toAbsolutePath().normalize());
    }

    /**
     * 异常消息：{@code getMessage()} 可能为 {@code null}，此时退回简单类名，避免事件消息里出现 "null"。
     */
    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

}
