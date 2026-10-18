package com.vidsage.domain.workspace.port;

import com.vidsage.domain.workspace.Workspace;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 媒体身份持久化的出口：领域侧只说「能做什么」，底下是 MySQL 还是别的存储，领域层不关心。
 */
public interface WorkspaceRepository {

    /**
     * 存一份新身份（与 subtitle_task 两表同插的一半），返回带着数据库自增 id 的那份——id 由数据库生成，
     * 调用方以返回值为准。
     *
     * @param workspace 待存的新身份（经 {@code Workspace.of} 创建）
     * @return 回填了数据库 id 的身份
     */
    Workspace insert(Workspace workspace);

    /**
     * 按内部行 id 查：管线从 task 拿到 {@code workspace_id} 后取它（读路径公式要用 source_key）。
     *
     * @param id 内部自增行 id
     * @return 身份；不存在时为空
     */
    Optional<Workspace> findById(long id);

    /**
     * 按身份键查（提交路径命中分支）。
     *
     * @param sourceType 来源类型
     * @param sourceKey  身份键
     * @return 身份；不存在时为空
     */
    Optional<Workspace> findBySourceTypeAndSourceKey(SourceType sourceType, SourceKey sourceKey);

    /**
     * 按身份键<b>锁定读</b>（提交路径并发分支）：两个请求同时提交同一个新 URL 时，
     * 撞 uk 的那个在同一事务里用它绕过一致性快照、读到对方刚提交的行。
     *
     * <p>是 {@code FOR SHARE} 不是 {@code FOR UPDATE}：只读不写，共享锁足够；duplicate-key 失败已在
     * 索引记录上留了 S 锁，{@code FOR UPDATE} 要 X 锁会变成 S→X 升级、两个撞键者互等而死锁。
     *
     * @param sourceType 来源类型
     * @param sourceKey  身份键
     * @return 身份；不存在时为空
     */
    Optional<Workspace> findForShareBySourceTypeAndSourceKey(SourceType sourceType, SourceKey sourceKey);

    /**
     * 写入内容指纹：一条 UPDATE 同时写两列并同句刷 {@code updated_at}。
     *
     * @param workspaceId 内部行 id
     * @param durationMs  媒体时长（毫秒）；取不到时为 {@code null}
     * @param publishedAt 发布时间；取不到时为 {@code null}
     * @param now         本次写入的时刻（同句刷 updated_at，由调用方传入以便对齐同一次收尾的时间戳）
     * @return 受影响行数
     */
    int updateFingerprint(long workspaceId, Integer durationMs, LocalDateTime publishedAt, LocalDateTime now);
}
