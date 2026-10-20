package com.vidsage.application.subtitle.pipeline;

import com.vidsage.application.media.pipeline.ContentFingerprintProbeDispatcher;
import com.vidsage.application.workspace.WorkspaceFingerprintStore;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.domain.workspace.model.ContentFingerprint;
import com.vidsage.domain.workspace.model.SourceType;
import com.vidsage.shared.progress.StageEvents;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 内容指纹探测阶段：主管线在媒体获取之前先问一句「内容还是不是上次那份」。
 *
 * <p>顺序写死：探测 → 比对 → 级联作废 → 写指纹。探测本身只读元数据、不落文件；比对是
 * 拿 {@code workspace} 记着的两列与探测值逐列比。内容变了才级联，级联与写指纹在
 * {@link WorkspaceFingerprintStore} 的同一个事务里。
 *
 * <p><b>「跳过探测」分两种，别混成一种</b>：
 * <ul>
 *   <li><b>路线本来没有这一维</b>（上传路线）：静默发一条 {@code SKIPPED} 就走，内容身份由 sha256 回答；</li>
 *   <li><b>有这一维却拿不到</b>（探测超时 / 元数据缺字段 / 远程路线没有可用的探测实现）：
 *       记 WARN 再跳过，让主管线按「有产物就复用」继续跑。</li>
 * </ul>
 * 两条都是降级路径，都不是失败路径：把取不到当异常抛出去，一次元数据风控就会把整条 run 判死。
 */
@Slf4j
@Component
public class SubtitleFingerprintCoordinator {

    private final ContentFingerprintProbeDispatcher probeDispatcher;
    private final WorkspaceFingerprintStore fingerprintStore;

    public SubtitleFingerprintCoordinator(ContentFingerprintProbeDispatcher probeDispatcher,
                                          WorkspaceFingerprintStore fingerprintStore) {
        this.probeDispatcher = probeDispatcher;
        this.fingerprintStore = fingerprintStore;
    }

    /**
     * 探测并处理内容变更。
     *
     * <p>路线先分叉再问探测器：上传路线本来就没有「绝对发布时间」这一维，直接静默跳过；
     * 只有远程路线才会去问「有没有探测实现」，问不到时记 WARN 再降级——那是装配问题，
     * 不是路线属性，静默会让「指纹两列永远为空、内容变了永远不级联」变得不可见。
     *
     * @param taskId   所属字幕任务（级联要按任务作废旧产物）
     * @param workspace 任务所属的媒体身份（带库里记着的旧指纹）
     * @param input    管线输入
     * @param workDir  工作区目录
     * @param listener 阶段进度监听
     * @throws RuntimeException 级联事务失败——必须让本次执行失败，不能带着旧产物继续跑
     */
    public void detectContentChange(long taskId, Workspace workspace, MediaInput input, Path workDir,
                                    ProgressListener listener) {
        String stageName = SubtitleStageName.PROBE_FINGERPRINT.getStageName();

        // 先按路线分叉：只有远程路线拿得到绝对发布时间，上传路线本来就没有这一维
        if (workspace.getSourceType() != SourceType.URL) {
            StageEvents.emit(listener, stageName, SubtitleStageName.PROBE_FINGERPRINT.end(),
                    "上传路线按内容摘要识别，无需探测内容指纹", StageProgress.State.SKIPPED);
            return;
        }
        // 远程路线本该有这一维，却没有实现认领探测器：这是装配问题，不是路线属性。
        // 静默跳过会让「指纹两列永远为空、内容变了永远不级联」变得不可见，故记 WARN
        if (!probeDispatcher.supports(input)) {
            log.warn("远程路线没有可用的内容指纹探测实现，跳过变更检测: taskId={}, workspaceId={}",
                    taskId, workspace.getId());
            StageEvents.emit(listener, stageName, SubtitleStageName.PROBE_FINGERPRINT.end(),
                    "没有可用的内容指纹探测实现，跳过变更检测", StageProgress.State.SKIPPED);
            return;
        }

        StageEvents.emit(listener, stageName, SubtitleStageName.PROBE_FINGERPRINT.start(),
                "开始探测内容指纹", StageProgress.State.RUNNING);

        Optional<ContentFingerprint> probed;
        try {
            probed = probeDispatcher.probe(input);
        } catch (RuntimeException e) {
            // 只记类名：异常消息可能带 URL / 站点输出（脱敏红线）
            log.warn("内容指纹探测失败，跳过变更检测: taskId={}, reason={}", taskId, e.getClass().getSimpleName());
            probed = Optional.empty();
        }
        if (probed.isEmpty()) {
            log.warn("内容指纹取不到，按「有产物就复用」继续: taskId={}, workspaceId={}", taskId, workspace.getId());
            StageEvents.emit(listener, stageName, SubtitleStageName.PROBE_FINGERPRINT.end(),
                    "取不到内容指纹，跳过变更检测", StageProgress.State.SKIPPED);
            return;
        }

        ContentFingerprint fingerprint = probed.get();
        if (workspace.hasUnknownFingerprint()) {
            fingerprintStore.writeFingerprint(workspace.getId(), fingerprint);
            StageEvents.emit(listener, stageName, SubtitleStageName.PROBE_FINGERPRINT.end(),
                    "首次探测，已记录内容指纹", StageProgress.State.SUCCEEDED);
            return;
        }
        if (workspace.matchesFingerprint(fingerprint)) {
            StageEvents.emit(listener, stageName, SubtitleStageName.PROBE_FINGERPRINT.end(),
                    "内容未变化", StageProgress.State.SUCCEEDED);
            return;
        }
        fingerprintStore.supersedeOnSourceMediaChange(taskId, workspace.getId(), fingerprint, workDir);
        StageEvents.emit(listener, stageName, SubtitleStageName.PROBE_FINGERPRINT.end(),
                "内容已变化，旧产物已作废，将重新产出", StageProgress.State.SUCCEEDED);
    }
}
