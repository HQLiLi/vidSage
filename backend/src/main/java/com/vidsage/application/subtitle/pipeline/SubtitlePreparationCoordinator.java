package com.vidsage.application.subtitle.pipeline;

import com.vidsage.application.media.IntermediateFileOwner;
import com.vidsage.application.media.pipeline.AudioPreparationCoordinator;
import com.vidsage.application.transcription.pipeline.Transcriber;
import com.vidsage.domain.media.model.AudioStageOutput;
import com.vidsage.domain.media.model.AudioSpec;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.subtitle.model.common.SubtitleStageOutput;
import com.vidsage.domain.subtitle.model.common.SubtitleArtifactSpec;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.transcription.model.AudioSource;
import com.vidsage.domain.transcription.model.TranscriptionResult;
import com.vidsage.domain.transcription.port.ASRProvider;
import com.vidsage.shared.fs.ArtifactFileUtils;
import com.vidsage.shared.progress.StageEvents;
import com.vidsage.shared.subtitle.SrtRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 字幕准备编排：从媒体音频中提取文本，落盘为字幕产物。
 *
 * <p><b>断点复用要过两道门</b>：调用方先回答「当前有没有完好的原始产物」（产物表那一侧），
 * 本类再回答「盘上的 segments JSON 在不在、能不能解析」。只看文件会把已经作废的原始产物当成
 * 有效检查点——内容变更级联只动数据库，不会去删干净盘上文件。调用方说不能复用，
 * 本类就完全忽略文件检查点，老老实实重跑。
 */
@Slf4j
@Component
public class SubtitlePreparationCoordinator {

    private final AudioPreparationCoordinator audioPreparationCoordinator;
    private final Transcriber transcriber;
    private final ASRProvider asrProvider;
    private final ObjectMapper objectMapper;

    /**
     * @param audioPreparationCoordinator 音频准备（获取媒体 + 抽取音频两阶段）
     * @param transcriber                 转写（复用云 provider）
     * @param asrProvider                 当前装配的 ASR provider（用来问规格与路线）
     * @param objectMapper                Jackson 3 序列化器（Boot 4 只有 tools.jackson 这份有 bean）
     */
    public SubtitlePreparationCoordinator(
            AudioPreparationCoordinator audioPreparationCoordinator,
            Transcriber transcriber,
            ASRProvider asrProvider,
            ObjectMapper objectMapper) {
        this.audioPreparationCoordinator = audioPreparationCoordinator;
        this.transcriber = transcriber;
        this.asrProvider = asrProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 产出一份字幕，不报告进度。
     *
     * @param input                  媒体输入（URL 或本地文件）
     * @param workDir                这条输入的工作区目录（调用方已定位好）
     * @param reuseCompletedOriginal 产物表里有没有完好的原始产物；没有就不许走文件检查点
     * @param owner                  中间产物归属（哪个任务、哪条 run）
     * @return 两个产物路径与元信息
     */
    public SubtitleStageOutput prepare(MediaInput input, Path workDir, boolean reuseCompletedOriginal,
                                       IntermediateFileOwner owner) {
        return prepare(input, workDir, ProgressListener.noop(), reuseCompletedOriginal, owner);
    }

    /**
     * 产出一份字幕：要音频 → 转写 → 写两个产物；产物表说可复用、且已有非空 segments JSON 才整段跳过。
     *
     * @param input                  媒体输入；不可为空
     * @param workDir                这条输入的工作区目录（调用方已定位好）；不可为空
     * @param listener               阶段进度监听；不可为 {@code null}——不关心进度用不带 listener 的重载
     * @param reuseCompletedOriginal 产物表里有没有完好的原始产物；{@code false} 时完全忽略文件检查点
     * @param owner                  中间产物归属；不可为空
     * @return 字幕产物（SRT 路径 + segments 路径 + 路线 + 语种 + 句数 + SRT 字节数）
     * @throws IllegalArgumentException 输入 / 监听 / 归属为空
     * @throws IllegalStateException    媒体获取 / 抽音频 / 转写 / 产物写入失败（由底层原样上抛或包装）
     */
    public SubtitleStageOutput prepare(MediaInput input, Path workDir, ProgressListener listener,
                                       boolean reuseCompletedOriginal, IntermediateFileOwner owner) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        if (listener == null) {
            throw new IllegalArgumentException("进度监听不能为 null：请传一个 ProgressListener，"
                    + "或改用不带 listener 的 prepare(input, workDir, reuseCompletedOriginal, owner) 重载");
        }
        if (owner == null) {
            throw new IllegalArgumentException("中间产物归属不能为空：请传 IntermediateFileOwner(taskId, runId)");
        }

        Path segmentsPath = workDir.resolve(SubtitleArtifactSpec.ORIGINAL_SEGMENTS.getFileName(null));
        Path srtPath = workDir.resolve(SubtitleArtifactSpec.ORIGINAL_SRT.getFileName(null));

        String transcribeAudioStageName = SubtitleStageName.TRANSCRIBE_AUDIO.getStageName();
        String writeSubtitlesStageName = SubtitleStageName.WRITE_SUBTITLES.getStageName();
        // 产物表说不能复用就完全别看文件：盘上的 JSON 可能是上一次内容留下的，解析得出来也不该用。
        // 只读 segments JSON，不看 SRT——SRT 是派生视图，即使 SRT 在而 JSON 不在也必须重跑
        SubtitleTrack existing = reuseCompletedOriginal
                ? ArtifactFileUtils.readJsonOrNull(segmentsPath, objectMapper, SubtitleTrack.class, "segments")
                : null;
        if (existing != null) {
            log.info("字幕断点复用跳过: segments={}", segmentsPath.getFileName());
            StageEvents.emit(listener, transcribeAudioStageName, SubtitleStageName.TRANSCRIBE_AUDIO.end(), "已有 segments 产物，跳过转写", StageProgress.State.SKIPPED);
            StageEvents.emit(listener, writeSubtitlesStageName, SubtitleStageName.WRITE_SUBTITLES.end(), "已有字幕产物，跳过写盘", StageProgress.State.SKIPPED);
            return toArtifact(srtPath, segmentsPath, existing);
        }

        AudioSpec spec = asrProvider.requiredAudioSpec();
        log.debug("开始准备字幕: workDir={}, audioSpec={}",
                workDir.getFileName(), spec.container());

        AudioStageOutput audio = audioPreparationCoordinator.prepare(input, workDir, spec, listener, owner);
        StageEvents.emit(listener, transcribeAudioStageName, SubtitleStageName.TRANSCRIBE_AUDIO.start(), "开始转写", StageProgress.State.RUNNING);
        TranscriptionResult result = transcriber.transcribe(
                new AudioSource.AudioFilePath(audio.audioPath().toString()), null);
        StageEvents.emit(listener, transcribeAudioStageName, SubtitleStageName.TRANSCRIBE_AUDIO.end(), "转写完成", StageProgress.State.SUCCEEDED);

        // 原始转写与关键词无关，指纹恒为 null——纠错发生在下游的 -clean 产物上
        SubtitleTrack track = new SubtitleTrack(result.language(), result.segments(), null);
        if (result.segments().isEmpty()) {
            // 空转写是合法产出：0 字节 SRT + 内容正常（segments:[]）的 JSON
            log.warn("ASR 未识别出任何内容，产出空字幕: segmentsPath={}，可能是纯音乐或无语音音频",
                    segmentsPath.getFileName());
        }
        StageEvents.emit(listener, writeSubtitlesStageName, SubtitleStageName.WRITE_SUBTITLES.start(),
                "开始写字幕产物", StageProgress.State.RUNNING);
        writeArtifacts(segmentsPath, srtPath, track);
        StageEvents.emit(listener, writeSubtitlesStageName, SubtitleStageName.WRITE_SUBTITLES.end(),
                "字幕产物写入完成", StageProgress.State.SUCCEEDED);
        return toArtifact(srtPath, segmentsPath, track);
    }

    /**
     * 写两个产物：先写 segments JSON（检查点），再写 SRT（由 track 渲染）。两者都原子替换。
     */
    private void writeArtifacts(Path segmentsPath, Path srtPath, SubtitleTrack track) {
        ArtifactFileUtils.writeJsonAtomically(track, objectMapper, segmentsPath);
        ArtifactFileUtils.writeAtomically(SrtRenderer.toSrt(track.segments()).getBytes(StandardCharsets.UTF_8), srtPath);
    }

    /**
     * 产物 → {@link SubtitleStageOutput}：从磁盘读 SRT 字节数（空转写时为 0）。
     */
    private static SubtitleStageOutput toArtifact(Path srtPath, Path segmentsPath, SubtitleTrack track) {
        long srtSizeBytes = ArtifactFileUtils.sizeOrZero(srtPath);
        return new SubtitleStageOutput(srtPath, segmentsPath, track.language(),
                track.segments().size(), srtSizeBytes);
    }

}
