package com.vidsage.domain.subtitle.port;

import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import java.util.List;
import java.util.Optional;

/**
 * 字幕产物版本持久化的出口：领域侧只说「能做什么」，底下是 MySQL 还是别的存储，领域层不关心。
 */
public interface SubtitleArtifactRepository {

    /**
     * 插一条 {@code CREATING} 占位。{@code revision} 按 {@code MAX+1}
     * （同 {@code (task, artifact_type, lang)}）生成并回填。
     *
     * @param artifact 待插的占位产物（经 {@code SubtitleArtifact.createCreatingArtifact} 创建）
     * @return 回填了数据库 id 与 revision 的产物
     */
    SubtitleArtifact insertCreating(SubtitleArtifact artifact);

    /**
     * 查当前有效的某一版产物（复用判据、读路径只认 {@code READY}）。
     *
     * @param taskId 所属字幕任务
     * @param type   产物类型
     * @param lang   语种；无语言维度传 {@code null} 或空串
     * @return 该 (task,type,lang) 的 {@code READY} 产物；没有时为空
     */
    Optional<SubtitleArtifact> findReadyByTaskIdAndTypeAndLang(long taskId, ArtifactType type, String lang);

    /**
     * 查该 task 当前全部有效产物（{@code /artifacts} 只给当前有效产物）。
     *
     * @param taskId 所属字幕任务
     * @return 全部 {@code READY} 产物；没有时是空列表
     */
    List<SubtitleArtifact> findReadyByTaskId(long taskId);

    /**
     * 换版落定：一条 UPDATE 同时写四列文件列 + 四列哈希列 + 置 {@code READY}。
     *
     * @param artifact 已 {@code completeCreation} 的产物（携带 id 与八个文件/哈希值）
     * @return 受影响行数
     */
    int updateReady(SubtitleArtifact artifact);

    /**
     * <b>覆盖型</b>取代：同 {@code (task,type,lang)} 换版时，旧行
     * {@code READY} → {@code SUPERSEDED} 并<b>清空文件列与哈希列</b>，停在 {@code SUPERSEDED}
     * （不再到 {@code DELETED}——文件名不带 revision，同名文件此刻已归新行）。
     *
     * @param artifactId 被取代的旧产物行 id
     * @return 受影响行数
     */
    int markSupersededAndClearFiles(long artifactId);

    /**
     * <b>孤儿型</b>取代（级联）：某任务某类型当前有效的产物一次全标掉，只动状态，
     * <b>文件列留着</b>——路径是 DB 的列，删文件时要用它。
     *
     * <p>级联的靶子按「任务 + 类型 + 当前有效」三件事定位，不靠派生关系：译文基于哪一版上游
     * 不写进判据，由「上游换版时把它标掉」保证。
     *
     * @param taskId 所属字幕任务
     * @param type   产物类型（级联只用于 {@code TRANSLATED}）
     * @return 受影响行数
     */
    int markSupersededByTaskIdAndType(long taskId, ArtifactType type);

    /**
     * 标已回收（两阶段第二步：文件确认回收之后）。
     *
     * @param artifactId 产物行 id
     * @return 受影响行数
     */
    int markDeleted(long artifactId);

    /**
     * 收口置位：本次 run 插的、仍停在 {@code CREATING} 的行 → {@code FAILED}
     * （靠 {@code derived_from_run_id} 定位）。
     *
     * @param runId 产出这些占位行的 run
     * @return 受影响行数
     */
    int markCreatingAsFailedByRunId(long runId);

    /**
     * 启动收口第二条：残留 {@code CREATING} → {@code FAILED}（无差别批量，不按 run 定位）。
     *
     * @return 受影响行数
     */
    int markCreatingAsFailed();
}
