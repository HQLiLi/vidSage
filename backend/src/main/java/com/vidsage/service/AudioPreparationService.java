package com.vidsage.service;

import com.vidsage.model.media.AcquiredMedia;
import com.vidsage.model.media.AudioArtifact;
import com.vidsage.model.media.AudioContainer;
import com.vidsage.model.media.AudioSpec;
import com.vidsage.model.media.MediaInput;
import com.vidsage.model.progress.ProgressListener;
import com.vidsage.model.progress.StageEvent;
import com.vidsage.model.progress.StageEvent.StageState;
import com.vidsage.provider.audio.AudioExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 音频准备编排：把「一条 {@link MediaInput} + 一个 {@link AudioSpec}」变成一份音频产物。
 *
 * <p>两个阶段：{@code acquire}（获取媒体）→ {@code extract-audio}（抽取音频）。每个阶段在开始 /
 * 成功 / 跳过 / 失败时各发一个 {@link StageEvent}；失败路径在发完 {@code FAILED}（消息取异常
 * {@code getMessage()}）后<b>原样上抛</b>，不吞异常。
 *
 * <p><b>断点复用</b>（产物存在且大小 &gt; 0 即跳过，0 字节视为不存在、必须重跑）：
 * <ul>
 *   <li>acquire 阶段由 {@link MediaAcquisitionService#reuseExisting} 判断——只有适配器知道
 *       {@code source.<ext>} 的推导规则与 info json 的解析；</li>
 *   <li>extract 阶段由本服务判断——音频产物名（{@code audio-<规格指纹>.<ext>}）只有本服务知道，
 *       且<b>先校验 spec 再判断点</b>，不放过「非法 spec + 文件恰好存在」这条路径。</li>
 * </ul>
 * 跳过时发 {@code SKIPPED} 事件并记 INFO 日志：<b>断点复用必须可观测</b>，否则「跳过了」与
 * 「没跑」分不出来。
 *
 * <p><b>音频产物名与进度权重都是过渡态</b>：真正的进度形状与阶段划分应由 MVP-1 的
 * 异步管线 / SSE payload 定锤，届时以那边为准。
 */
@Slf4j
@Service
public class AudioPreparationService {

    /** acquire 阶段名。 */
    static final String STAGE_ACQUIRE = "acquire";

    /** extract-audio 阶段名。 */
    static final String STAGE_EXTRACT = "extract-audio";

    /**
     * 进度权重（<b>过渡约定</b>，形状以 MVP-1 的 SSE payload 为准）：acquire 0→50，extract 50→100。
     * SKIPPED 沿用该阶段本应到达的累计值，保证进度单调、不被跳过阶段回退。
     */
    static final int ACQUIRE_START = 0;

    static final int ACQUIRE_DONE = 50;

    static final int EXTRACT_START = 50;

    static final int EXTRACT_DONE = 100;

    private final WorkspaceService workspaceService;
    private final MediaAcquisitionService acquisitionService;
    private final AudioExtractor audioExtractor;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param workspaceService   工作目录解析
     * @param acquisitionService 媒体获取分派（不感知任何适配器实现类）
     * @param audioExtractor     音频抽取（不感知 ffmpeg 实现类）
     */
    public AudioPreparationService(WorkspaceService workspaceService,
                                   MediaAcquisitionService acquisitionService,
                                   AudioExtractor audioExtractor) {
        this.workspaceService = workspaceService;
        this.acquisitionService = acquisitionService;
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
            // 不把 null 静默当成 noop()：那会让「忘了传 listener」变成进度无声消失，
            // 正是本项目一直在避免的无声失败（design §六 / 裁定改 3）
            throw new IllegalArgumentException("进度监听不能为 null：请传一个 ProgressListener，"
                    + "或改用不带 listener 的 prepare(input, spec) 两参重载（内部走 ProgressListener.noop()）");
        }

        Path workDir = workspaceService.workspaceFor(input);
        log.debug("开始准备音频: workDir={}, container={}", workDir.getFileName(), spec.container());

        AcquiredMedia media = runAcquireStage(input, workDir, listener);
        return runExtractStage(media, workDir, spec, listener);
    }

    /**
     * acquire 阶段：已有完整产物则跳过，否则委托获取。
     */
    private AcquiredMedia runAcquireStage(MediaInput input, Path workDir, ProgressListener listener) {
        try {
            Optional<AcquiredMedia> existing = acquisitionService.reuseExisting(input, workDir);
            if (existing.isPresent()) {
                log.info("acquire 阶段跳过（断点复用）: media={}", existing.get().mediaPath().getFileName());
                emit(listener, STAGE_ACQUIRE, ACQUIRE_DONE, "已有本地媒体，跳过获取", StageState.SKIPPED);
                return existing.get();
            }
            emit(listener, STAGE_ACQUIRE, ACQUIRE_START, "开始获取媒体", StageState.RUNNING);
            AcquiredMedia media = acquisitionService.acquire(input, workDir);
            emit(listener, STAGE_ACQUIRE, ACQUIRE_DONE, "媒体获取完成", StageState.SUCCEEDED);
            return media;
        } catch (RuntimeException e) {
            emit(listener, STAGE_ACQUIRE, ACQUIRE_START, "获取媒体失败：" + describe(e), StageState.FAILED);
            throw e;
        }
    }

    /**
     * extract-audio 阶段：判是否有非空音频产物——有则跳过，否则委托抽取。
     */
    private AudioArtifact runExtractStage(AcquiredMedia media, Path workDir, AudioSpec spec,
                                          ProgressListener listener) {
        try {
            // spec 字段合法性已由 AudioSpec 的紧凑构造器结构性保证（见 model/media/AudioSpec.java），
            // 故此处不再前置校验。
            //
            // 历史：本处原有一次 requireValidSpec 调用（当时注释为「改 2：spec 校验必须前置到断点判断
            // 之前」），理由是断点分支会直接返回产物、不经过抽取实现，若不先校验，「传了非法 spec 而
            // 对应文件恰好存在」会返回一个未经校验的产物。该防御意图仍然成立——非法 spec 不得被断点
            // 分支放过；但场景本身在紧凑构造器落地后已不可能构造（非法 AudioSpec 不存在），故本服务
            // 与 FfmpegAudioExtractor 两处 requireValidSpec 一并移除，由构造器单点保证。
            Path audioPath = workDir.resolve(audioFileName(spec));
            long existingSize = nonEmptySize(audioPath);
            if (existingSize > 0) {
                log.info("extract 阶段跳过（断点复用）: file={}, sizeBytes={}",
                        audioPath.getFileName(), existingSize);
                emit(listener, STAGE_EXTRACT, EXTRACT_DONE, "已有音频产物，跳过抽取", StageState.SKIPPED);
                return new AudioArtifact(audioPath, spec, existingSize);
            }
            emit(listener, STAGE_EXTRACT, EXTRACT_START, "开始抽取音频", StageState.RUNNING);
            AudioArtifact artifact = audioExtractor.extract(media.mediaPath(), audioPath, spec);
            emit(listener, STAGE_EXTRACT, EXTRACT_DONE, "音频抽取完成", StageState.SUCCEEDED);
            return artifact;
        } catch (RuntimeException e) {
            emit(listener, STAGE_EXTRACT, EXTRACT_START, "抽取音频失败：" + describe(e), StageState.FAILED);
            throw e;
        }
    }

    /**
     * 音频产物文件名，带<b>完整规格指纹</b>：
     * {@code audio-<采样率>k<声道>ch[-<码率>k]-t<音频轨号>.<扩展名>}，如
     * {@code audio-16k1ch48k-t0.m4a}、{@code audio-16k1ch-t1.wav}（无损容器无码率段）。
     *
     * <p><b>为什么必须带完整指纹</b>：若只按容器命名（{@code audio.wav}），同一输入先用 16k 抽一次、
     * 再用 8k（或码率 / 轨号不同）抽一次时，第二次会命中第一次的文件被断点判据当成已完成而跳过，
     * 返回一份<b>与请求规格不符</b>的音频且不报错——正是「让正确性依赖未言明的假设」。指纹因此覆盖
     * <b>所有影响产物内容的参数</b>（采样率、声道、码率、音频轨号）。
     *
     * <p><b>不做省略特例</b>：轨号为 0 也照写 {@code -t0}，不为它开分支——特例就是漏判的入口。
     *
     * <p>包级 static：可直测。<b>spec 的容器非空、各数值已合法由 {@link AudioSpec} 的紧凑构造器
     * 保证</b>；这些名字<b>只有本服务知道</b>（见 design §六 文件表）。
     *
     * @param spec 已校验的音频规格
     * @return 音频产物文件名（不含目录）
     */
    static String audioFileName(AudioSpec spec) {
        StringBuilder name = new StringBuilder("audio-")
                .append(sampleRateKilo(spec.sampleRateHz()))
                .append(spec.channels())
                .append("ch");
        if (spec.container() == AudioContainer.M4A) {
            // 只有有损容器写码率段；无损容器（WAV）契约规定 bitrateKbps = 0 表示不适用
            name.append(spec.bitrateKbps()).append('k');
        }
        name.append("-t").append(spec.audioTrackIndex());
        return name.append('.').append(spec.container().name().toLowerCase(Locale.ROOT)).toString();
    }

    /**
     * 采样率 → 文件名里的 kHz 片段（{@code 16000 → 16k}、{@code 8000 → 8k}）。
     *
     * <p>非整千赫兹保留小数（如 {@code 44100 → 44.1k}）：整数截断会让不同采样率映射到同一名字，
     * 重新引入「按容器命名」那类静默复用漏洞。
     */
    static String sampleRateKilo(int sampleRateHz) {
        if (sampleRateHz % 1000 == 0) {
            return (sampleRateHz / 1000) + "k";
        }
        return (sampleRateHz / 1000.0) + "k";
    }

    /**
     * 产物可用时的字节数；不存在或为 0 字节时返回 0（0 表示「视为不存在」）。
     */
    private static long nonEmptySize(Path path) {
        try {
            return Files.isRegularFile(path) ? Math.max(Files.size(path), 0) : 0L;
        } catch (IOException e) {
            log.debug("读取音频产物大小失败，按不存在处理: file={}, reason={}", path.getFileName(), e.getMessage());
            return 0L;
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
