package com.vidsage.domain.task;

import com.vidsage.domain.shared.WorkspaceKey;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Getter;

/**
 * 视频任务：任务生命周期<b>状态机</b>的载体。放进 {@code domain/task/} 正是
 * directory-structure.md 预告的升级触发条件——这里有会<b>拒绝非法状态迁移</b>的行为方法，
 * 如 SUCCEEDED → RUNNING。
 *
 * <p>主状态机合法迁移只有三条，与数据库 {@code task.status} ENUM 及 TaskStatus 契约一一对应：
 * <ul>
 *   <li>{@link #start()}：PENDING → RUNNING（管线开始执行）</li>
 *   <li>{@link #succeed()}：RUNNING → SUCCEEDED（全部阶段完成）</li>
 *   <li>{@link #fail(String)}：RUNNING → FAILED（任一阶段失败）</li>
 * </ul>
 * SUCCEEDED / FAILED 是终态，任何再迁移、以及 PENDING 直接跳到终态，都抛 {@link IllegalStateException}。
 *
 * <p><b>翻译子状态机（2026-10-02 两段式）</b>：翻译不随主管线自动执行，主任务 SUCCEEDED 后由用户
 * 按需触发，{@code translationStatus}（null = 从未触发）刻画这段第二生命周期——
 * {@link #translationStart()} / {@link #translationSucceed()} / {@link #translationFail(String)}，
 * 仅当主状态 SUCCEEDED 后允许流转，非法迁移同样抛 {@link IllegalStateException}；主状态不因翻译
 * 失败而回退。两台状态机共享 updatedAt，翻译失败信息复用 errorMessage（「翻译失败：」前缀）。
 *
 * <p>字段不开放 setter：状态与时间戳只能经迁移方法变更；阶段名经 {@link #updateStage(String)} 变更
 * （限 RUNNING 期间）。时间戳随迁移自动落，无需调用方操心。
 *
 * <p>{@code id} 是数据库内部行 id（persistence 层回填），<b>不对外</b>——对外只暴露 taskNo。
 * 重建已持久化任务只能走 {@link #restore}（仅供 persistence 层使用）。
 */
@Getter
public class VideoTask {

    /** 数据库内部行 id；insert 回填，新建任务在落库前为 {@code null}。 */
    private Long id;

    /** 对外任务号值对象：32 位短 UUID 语义（去掉连字符的随机 UUID，恰好装进 varchar(32)），见 {@link TaskNo}。 */
    private final TaskNo taskNo;

    /** 输入类型。 */
    private final TaskInputType inputType;

    /** 来源：URL 原文或上传文件原始名（日志不落原值）。 */
    private final String sourceUri;

    /** 工作区短哈希（查重用，见 {@link WorkspaceKey}）：URL / 本地路径取 normalize 串摘要，上传取内容 sha256 前 12 位。 */
    private final WorkspaceKey workspaceKey;

    /** 工作区目录相对路径（相对 vidsage.workspace.root）。 */
    private final String workspaceDir;

    /** 任务状态（状态机权威存储）。 */
    private TaskStatus status;

    /** 最近阶段名（与 StageEvent.stage 同词表）。 */
    private String currentStage;

    /**
     * 翻译子状态（2026-10-02 两段式）：{@code null} = 从未触发；仅主状态 SUCCEEDED 后
     * 经迁移方法流转。与主状态 {@link #status} 相互独立。
     */
    private TranslationStatus translationStatus;

    /** 终态错误信息（中文，面向用户；主任务失败或翻译子状态失败——后者带「翻译失败：」前缀）。 */
    private String errorMessage;

    /** 创建时间。 */
    private final LocalDateTime createdAt;

    /** 最近变更时间。 */
    private LocalDateTime updatedAt;

    /** 开始执行时间。 */
    private LocalDateTime startedAt;

    /** 终态时间。 */
    private LocalDateTime finishedAt;

    /**
     * 私有构造：创建入口只有 {@link #newPending} 与 {@link #restore}。
     */
    private VideoTask(Long id, TaskNo taskNo, TaskInputType inputType, String sourceUri,
                      WorkspaceKey workspaceKey, String workspaceDir, TaskStatus status, String currentStage,
                      TranslationStatus translationStatus, String errorMessage, LocalDateTime createdAt,
                      LocalDateTime updatedAt, LocalDateTime startedAt, LocalDateTime finishedAt) {
        this.id = id;
        this.taskNo = taskNo;
        this.inputType = inputType;
        this.sourceUri = sourceUri;
        this.workspaceKey = workspaceKey;
        this.workspaceDir = workspaceDir;
        this.status = status;
        this.currentStage = currentStage;
        this.translationStatus = translationStatus;
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
                TaskStatus.PENDING, null, null, null, now, now, null, null);
    }

    /**
     * 重建已持久化的任务：仅供 persistence 层把数据库行还原成领域对象使用，业务代码不得用它凭空造任务。
     *
     * @param translationStatus 翻译子状态；数据库 NULL（从未触发）以 {@code null} 传入
     */
    public static VideoTask restore(Long id, TaskNo taskNo, TaskInputType inputType, String sourceUri,
                                    WorkspaceKey workspaceKey, String workspaceDir, TaskStatus status,
                                    String currentStage, TranslationStatus translationStatus,
                                    String errorMessage, LocalDateTime createdAt,
                                    LocalDateTime updatedAt, LocalDateTime startedAt, LocalDateTime finishedAt) {
        return new VideoTask(id, taskNo, inputType, sourceUri, workspaceKey, workspaceDir, status,
                currentStage, translationStatus, errorMessage, createdAt, updatedAt, startedAt, finishedAt);
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
     * @param errorMessage 终态错误信息（中文，面向用户；由调用方组装「缺什么、怎么补」）
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
     * @param stage 阶段名（与 StageEvent.stage 同词表：acquire / extract-audio / transcribe /
     *              write-subtitles / clean / translate）
     * @throws IllegalStateException 任务不处于 RUNNING
     */
    public void updateStage(String stage) {
        requireStatus(TaskStatus.RUNNING, "推进阶段（任务不在运行中）");
        currentStage = stage;
        updatedAt = LocalDateTime.now();
    }

    /**
     * 翻译子状态启动（2026-10-02 两段式）：主任务 SUCCEEDED 后按需触发翻译。允许从「从未触发」
     * （null）、失败（FAILED，失败重试）与已完成（SUCCEEDED，检查点被删后的重跑）进入；
     * 翻译进行中（RUNNING）重复触发被拒绝——调用方以此保证「不重入」。
     *
     * @throws IllegalStateException 主任务不在 SUCCEEDED，或翻译已在进行中
     */
    public void translationStart() {
        requireMainSucceededForTranslation("启动翻译");
        if (translationStatus == TranslationStatus.RUNNING) {
            throw new IllegalStateException("非法状态迁移：翻译已在进行中（RUNNING），不得重复触发");
        }
        translationStatus = TranslationStatus.RUNNING;
        updatedAt = LocalDateTime.now();
    }

    /**
     * 翻译子状态收敛为 SUCCEEDED：正常完成（RUNNING → SUCCEEDED）与幂等收敛（触发时发现翻译
     * 产物已在，从不触发 / 失败直接收敛）都走这里。主状态保持 SUCCEEDED 不变。
     *
     * @throws IllegalStateException 主任务不在 SUCCEEDED，或翻译已处于 SUCCEEDED（无需再收敛）
     */
    public void translationSucceed() {
        requireMainSucceededForTranslation("收敛翻译为 SUCCEEDED");
        if (translationStatus == TranslationStatus.SUCCEEDED) {
            throw new IllegalStateException("非法状态迁移：翻译已处于 SUCCEEDED，不得重复收敛");
        }
        translationStatus = TranslationStatus.SUCCEEDED;
        updatedAt = LocalDateTime.now();
    }

    /**
     * 翻译子状态迁移到 FAILED：只有进行中的翻译可以失败。错误信息复用 {@code errorMessage}
     * （由调用方带「翻译失败：」前缀组装），主状态保持 SUCCEEDED 不变——主状态机的终态错误与
     * 翻译子状态的失败原因共用一列，同一任务同一时刻只有一种「最近终态失败」。
     *
     * @param errorMessage 翻译失败原因（中文，面向用户；由调用方组装「缺什么、怎么补」）
     * @throws IllegalStateException 主任务不在 SUCCEEDED，或翻译不处于 RUNNING
     */
    public void translationFail(String errorMessage) {
        requireMainSucceededForTranslation("翻译标记失败");
        if (translationStatus != TranslationStatus.RUNNING) {
            throw new IllegalStateException("非法状态迁移：翻译失败要求当前翻译状态为 RUNNING，当前为 "
                    + translationStatus);
        }
        translationStatus = TranslationStatus.FAILED;
        this.errorMessage = errorMessage;
        updatedAt = LocalDateTime.now();
    }

    /**
     * 翻译子状态流转的公共前置守卫：翻译只在主任务成功（SUCCEEDED）后的第二生命周期里流转，
     * 其他主状态下一律拒绝。
     */
    private void requireMainSucceededForTranslation(String action) {
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
