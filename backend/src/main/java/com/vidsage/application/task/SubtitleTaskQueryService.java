package com.vidsage.application.task;

import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.application.task.message.TaskRunProgress;
import com.vidsage.application.task.query.KeywordsSnapshot;
import com.vidsage.application.task.query.RunSummary;
import com.vidsage.application.task.query.TaskSnapshot;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.port.SubtitleKeywordRepository;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.task.model.RunRef;
import com.vidsage.domain.task.port.TaskRunRepository;
import com.vidsage.domain.workspace.Workspace;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 任务查询：任务视图、关键词清单，以及某条 run 的 SSE 快照。
 *
 * <p>只读不写——提交、上传、重生成、翻译在 {@link SubtitleTaskService}。
 * 定位任务与它的媒体走 {@link SubtitleTaskFinder}。
 */
@Service
public class SubtitleTaskQueryService {

    private final SubtitleTaskFinder finder;
    private final TaskRunRepository taskRunRepository;
    private final SubtitleKeywordRepository keywordRepository;

    public SubtitleTaskQueryService(SubtitleTaskFinder finder,
                                    TaskRunRepository taskRunRepository,
                                    SubtitleKeywordRepository keywordRepository) {
        this.finder = finder;
        this.taskRunRepository = taskRunRepository;
        this.keywordRepository = keywordRepository;
    }

    /**
     * 按对外任务号查询任务视图：身份 + 最近一次执行。
     *
     * @param taskNo 对外任务号；不可为空白
     * @return 任务视图（从未执行时 lastRun 为 {@code null}）
     * @throws IllegalArgumentException taskNo 为空白（HTTP 边界映射 400）
     * @throws TaskNotFoundException    任务不存在（HTTP 边界映射 404）
     */
    public TaskSnapshot findByTaskNo(String taskNo) {
        SubtitleTask task = finder.require(taskNo);
        Workspace workspace = finder.requireWorkspace(task);
        RunSummary lastRun = taskRunRepository.findLatestByTaskId(task.getId())
                .map(RunSummary::from)
                .orElse(null);
        return new TaskSnapshot(task.getTaskNo().value(), workspace.getSourceType(), workspace.getSourceUri(),
                task.getCreatedAt(), lastRun);
    }

    /**
     * 读回该任务的关键词清单与指纹（前端拿指纹与产物里的 keywordsFingerprint 比对，
     * 判断「这份字幕对不对得上当前关键词」）。
     *
     * @param taskNo 对外任务号；不可为空白
     * @return 关键词清单（用户填写顺序）与指纹；从没填过时为空清单（不是 null）
     * @throws TaskNotFoundException 任务不存在（404）
     */
    public KeywordsSnapshot findKeywords(String taskNo) {
        SubtitleTask task = finder.require(taskNo);
        SubtitleKeywords keywords = keywordRepository.findByTaskId(task.getId());
        return new KeywordsSnapshot(keywords.values(), keywords.computeFingerprint());
    }

    /**
     * SSE 端点用：按 {@code (taskNo, runNo)} 取这条 run 的连接建立快照，顺带回答「该不该开流」。
     *
     * @param taskNo 对外任务号；不可为空白
     * @param runNo  任务内递增序号
     * @return 仍在跑：这条 run 的首条 DB 快照；<b>已到终态：空</b>（端点据此回 204，不建流）
     * @throws IllegalArgumentException taskNo 为空白（400）
     * @throws TaskNotFoundException    任务不存在或该任务没有这个 runNo（404）
     */
    public Optional<TaskRunProgress> streamSnapshot(String taskNo, int runNo) {
        return Optional.of(renderSnapshot(taskNo, runNo)).filter(snapshot -> !snapshot.isTerminal());
    }

    /**
     * 渲染某条 run 的当前快照（RUNNING 与终态都渲染）：SSE 端点建好连接后复查用——
     * 注册连接与取快照之间有毫秒级窗口，复查拿到终态时要推一条终态快照把流收掉，而不是静默关。
     *
     * @param taskNo 对外任务号；不可为空白
     * @param runNo  任务内递增序号
     * @return 这条 run 的当前快照（无论 RUNNING 还是终态）
     * @throws IllegalArgumentException taskNo 为空白（400）
     * @throws TaskNotFoundException    任务不存在或该任务没有这个 runNo（404）
     */
    public TaskRunProgress renderSnapshot(String taskNo, int runNo) {
        SubtitleTask task = finder.require(taskNo);
        TaskRun run = taskRunRepository.findByTaskIdAndRunNo(task.getId(), runNo)
                .orElseThrow(() -> new TaskNotFoundException(taskNo));
        return TaskRunProgress.of(RunRef.of(task.getTaskNo(), run), run, null);
    }
}
