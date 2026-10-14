package com.vidsage.domain.task;

import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.domain.task.model.TaskInputType;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.task.model.TranslationStatus;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 视频任务：任务生命周期<b>状态机</b>的载体——它带有<b>拒绝非法状态迁移</b>的行为方法（如
 * SUCCEEDED → RUNNING），所以由 domain 承载，而不是只做数据容器的实体。
 *
 * <p>主状态机的合法迁移只有三条（{@link #start()} / {@link #succeed()} / {@link #fail(String)}，
 * 见各自的方法注释）。SUCCEEDED / FAILED 是终态，任何再迁移、以及 PENDING 直接跳到终态，
 * 都抛 {@link IllegalStateException}。
 *
 * <p>状态与时间戳只能经迁移方法变更；{@code id} 是数据库内部行 id（persistence
 * 层回填），<b>不对外</b>——对外只暴露 taskNo。重建已持久化任务只能走 {@link #restore}。
 */
@Getter
public class VideoTask {

    /**
     * 数据库内部行 id；insert 回填，新建任务在落库前为 {@code null}。
     */
    private Long id;

    /**
     * 对外任务号值对象：32 位短 UUID 语义（去掉连字符的随机 UUID，恰好装进 varchar(32)），见 {@link TaskNo}。
     */
    private final TaskNo taskNo;

    /**
     * 输入类型。
     */
    private final TaskInputType inputType;

    /**
     * 来源：URL 原文或上传文件原始名。
     *
     * <p><b>约束：本字段值不得直接写入日志</b>——含用户原始文件名，属敏感信息。
     */
    private final String sourceUri;

    /**
     * 工作区短哈希（查重用，见 {@link WorkspaceKey}）。
     */
    private final WorkspaceKey workspaceKey;

    /**
     * 工作区目录相对路径（相对 vidsage.workspace.root）。
     */
    private final String workspaceDir;

    /**
     * 任务状态（状态机权威存储）。
     */
    private TaskStatus status;

    /**
     * 最近阶段名（与 StageProgress.stage 同词表）。
     */
    private String currentStage;


    /**
     * 终态错误信息（中文，面向用户）。主任务失败、翻译子状态失败、重新生成失败
     * 共用本字段，各自带前缀区分（{@link #TASK_FAILURE_PREFIX} / {@link #TRANSLATION_FAILURE_PREFIX} /
     * {@link #REGENERATE_FAILURE_PREFIX}）——同一任务同一时刻只有一种「最近失败」。
     */
    private String errorMessage;

    /**
     * 创建时间。
     */
    private final LocalDateTime createdAt;

    /**
     * 最近变更时间。
     */
    private LocalDateTime updatedAt;

    /**
     * 开始执行时间。
     */
    private LocalDateTime startedAt;

    /**
     * 终态时间。
     */
    private LocalDateTime finishedAt;

    /**
     * 私有构造：创建入口只有 {@link #newPending} 与 {@link #restore}。
     */
    private VideoTask(Long id, TaskNo taskNo, TaskInputType inputType, String sourceUri,
                      WorkspaceKey workspaceKey, String workspaceDir, TaskStatus status, String currentStage,
                      String errorMessage, LocalDateTime createdAt,
                      LocalDateTime updatedAt, LocalDateTime startedAt, LocalDateTime finishedAt) {
        this.id = id;
        this.taskNo = taskNo;
        this.inputType = inputType;
        this.sourceUri = sourceUri;
        this.workspaceKey = workspaceKey;
        this.workspaceDir = workspaceDir;
        this.status = status;
        this.currentStage = currentStage;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    /**
     * 新建一条排队中的任务：状态 PENDING、生成对外 taskNo、落创建时间戳；翻译子状态为
     * 「从未触发」（null）。
     *
     * @param inputType    输入类型
     * @param sourceUri    来源（URL 原文或上传文件原始名）
     * @param workspaceKey 工作区短哈希
     * @param workspaceDir 工作区目录相对路径
     * @return 状态为 PENDING 的新任务（id 未落库，为 {@code null}）
     */
    public static VideoTask newPending(TaskInputType inputType, String sourceUri,
                                       WorkspaceKey workspaceKey, String workspaceDir) {
        LocalDateTime now = LocalDateTime.now();
        return new VideoTask(null, new TaskNo(newTaskNo()), inputType, sourceUri, workspaceKey, workspaceDir,
                TaskStatus.PENDING, null, null, now, now, null, null);
    }

    /**
     * 重建已持久化的任务：仅供 persistence 层把数据库行还原成领域对象使用，业务代码不得用它凭空造任务。
     */
    public static VideoTask restore(Long id, TaskNo taskNo, TaskInputType inputType, String sourceUri,
                                    WorkspaceKey workspaceKey, String workspaceDir, TaskStatus status,
                                    String currentStage,
                                    String errorMessage, LocalDateTime createdAt,
                                    LocalDateTime updatedAt, LocalDateTime startedAt, LocalDateTime finishedAt) {
        return new VideoTask(id, taskNo, inputType, sourceUri, workspaceKey, workspaceDir, status,
                currentStage, errorMessage, createdAt, updatedAt, startedAt, finishedAt);
    }

    /**
     * PENDING → RUNNING：管线开始执行，落 startedAt。
     *
     * @throws IllegalStateException 任务不处于 PENDING（含重复启动与终态任务再启动）
     */
    public void start() {
        requireStatus(TaskStatus.PENDING, "RUNNING（只有 PENDING 状态的任务可以启动）");
        status = TaskStatus.RUNNING;
        startedAt = LocalDateTime.now();
        updatedAt = startedAt;
    }

    /**
     * RUNNING → SUCCEEDED：全部阶段完成，落 finishedAt。
     *
     * @throws IllegalStateException 任务不处于 RUNNING
     */
    public void succeed() {
        requireStatus(TaskStatus.RUNNING, "SUCCEEDED（只有 RUNNING 状态的任务可以标记成功）");
        status = TaskStatus.SUCCEEDED;
        finishedAt = LocalDateTime.now();
        updatedAt = finishedAt;
    }

    /**
     * RUNNING → FAILED：任一阶段失败，落错误信息与 finishedAt。
     *
     * @param errorMessage 终态错误信息（中文，面向用户；由调用方带 {@link #TASK_FAILURE_PREFIX}
     *                     组装「缺什么、怎么补」）
     * @throws IllegalStateException 任务不处于 RUNNING
     */
    public void fail(String errorMessage) {
        requireStatus(TaskStatus.RUNNING, "FAILED（只有 RUNNING 状态的任务可以标记失败）");
        status = TaskStatus.FAILED;
        this.errorMessage = errorMessage;
        finishedAt = LocalDateTime.now();
        updatedAt = finishedAt;
    }

    /**
     * 推进最近阶段名（限 RUNNING 期间，由进度回调或管线阶段边界驱动），同时刷新 updatedAt。
     *
     * @param stage 阶段名
     * @throws IllegalStateException 任务不处于 RUNNING
     */
    public void updateStage(String stage) {
        requireStatus(TaskStatus.RUNNING, "推进阶段（任务不在运行中）");
        currentStage = stage;
        updatedAt = LocalDateTime.now();
    }

    /**
     * 记一次后置会话（重新生成字幕）的失败原因：主状态保持 SUCCEEDED——旧字幕一个字没少、还能用，
     * 重生成失败只是「这次更新没成」，不是任务失败，所以不能走 {@link #fail}
     * （SUCCEEDED 是终态，不许再迁移）。只刷新 {@code errorMessage} 与 {@code updatedAt}，
     * 由调用方带 {@link #REGENERATE_FAILURE_PREFIX} 组装并广播收口。
     *
     * @param errorMessage 失败原因（中文，面向用户；调用方带前缀组装）
     * @throws IllegalStateException 主任务不在 SUCCEEDED（后置会话的前置条件被破）
     */
    public void recordRegenerateFailure(String errorMessage) {
        requireMainSucceededForPostLifecycle("记录重新生成失败");
        this.errorMessage = errorMessage;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 主管线失败的前缀：{@link #fail} 落的文案都以它开头，由 SubtitlePipeline 组装。
     */
    public static final String TASK_FAILURE_PREFIX = "任务执行失败：";

    /**
     * 翻译失败的前缀：由 SubtitlePipeline 组装。
     * {@link #clearRegenerateFailure} 不认领它——翻译是另一台生命周期，不是重新生成。
     */
    public static final String TRANSLATION_FAILURE_PREFIX = "翻译失败：";

    /**
     * 重新生成失败原因的前缀：{@link #recordRegenerateFailure} 落的文案都以它开头，
     * 下次重生成成功时按前缀认领并清掉——旧失败文案挂在成功的任务视图上会误导用户。
     * 单源在这里，组装方与清除方共用。
     */
    public static final String REGENERATE_FAILURE_PREFIX = "重新生成失败：";

    /**
     * 重新生成成功后清掉上一次的失败原因：<b>只认领自己那个前缀</b>——
     * 主管线失败（终态后重生成根本不会跑）与翻译失败（{@link #TRANSLATION_FAILURE_PREFIX}，另一台生命周期）
     * 的错误信息一概不动。
     */
    public void clearRegenerateFailure() {
        if (errorMessage != null && errorMessage.startsWith(REGENERATE_FAILURE_PREFIX)) {
            errorMessage = null;
            updatedAt = LocalDateTime.now();
        }
    }

    /**
     * 「进行中」状态集合（PENDING / RUNNING）：提交查重的 active 判据单源——同一输入已有任务在
     * 排队或执行即视为冲突，终态任务不算 active（重新提交同一输入是断点复用的正常路径）。
     * {@code VideoTaskRepository.findActiveByWorkspaceKey} 的 SQL 过滤据此构造：业务谓词不写在
     * infrastructure 里，规则只有这一处定义。
     */
    public static final Set<TaskStatus> ACTIVE_STATUSES = Set.of(TaskStatus.PENDING, TaskStatus.RUNNING);

    /**
     * 任务是否已经结束：主状态是终态（SUCCEEDED / FAILED），且翻译没在进行中。
     * 用于事件分发——返回 {@code true} 表示该任务不会再产生后续状态事件。
     *
     * @param mainStatus 任务主状态
     * @return 任务是否结束
     */
    public static boolean isEventTerminal(TaskStatus mainStatus) {
        return mainStatus == TaskStatus.SUCCEEDED || mainStatus == TaskStatus.FAILED;
    }

    /**
     * 后置生命周期流转的公共前置守卫：翻译、重新生成等第二生命周期动作只在主任务成功
     * （SUCCEEDED）后允许，其他主状态下一律拒绝。
     */
    private void requireMainSucceededForPostLifecycle(String action) {
        if (status != TaskStatus.SUCCEEDED) {
            throw new IllegalStateException("非法状态迁移：" + action + "只能在主任务成功（SUCCEEDED）后进行，"
                    + "当前主状态为 " + status);
        }
    }

    /**
     * 状态守卫：当前状态与迁移目标不符即拒绝，消息给出「当前状态 → 目标状态」与合法前提。
     */
    private void requireStatus(TaskStatus required, String targetDescription) {
        if (status != required) {
            throw new IllegalStateException("非法状态迁移：" + status + " → " + targetDescription
                    + "，当前状态为 " + status);
        }
    }

    /**
     * 生成对外任务号：32 位十六进制（去掉连字符的随机 UUID），装进 varchar(32)。
     */
    private static String newTaskNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
