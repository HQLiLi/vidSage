package com.vidsage.model.task;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 翻译子状态（2026-10-02 两段式改造新增）：翻译不随主管线自动执行，主任务 SUCCEEDED 后由用户
 * 按需触发（{@code POST /api/tasks/{taskNo}/translate}），本枚举刻画这段第二生命周期的位置。
 *
 * <p>与主状态 {@link TaskStatus} 相互独立：翻译流转只发生在主状态 SUCCEEDED 之后，主状态不会因
 * 翻译失败而回退。持久化在 {@code task.translation_status}（ENUM 同名词表）；
 * <b>值为 {@code null} 表示从未触发</b>——刻意不用 NONE 之类的占位枚举值，让「从未触发」与
 * 数据库 NULL、JSON 缺省三者天然同形。
 */
@Schema(description = "翻译子状态：RUNNING（翻译中）/ SUCCEEDED（已完成，含幂等收敛）/ FAILED（失败）；"
        + "null 表示从未触发（主任务成功后按需触发翻译）")
public enum TranslationStatus {

    /** 翻译执行中（触发后、到终态前；含信号量排队等待）。 */
    RUNNING,

    /** 翻译完成；含幂等收敛——触发时发现翻译产物已在（检查点非空），未发 LLM 直接收敛为此状态。 */
    SUCCEEDED,

    /** 翻译失败；原因复用 task.error_message（带「翻译失败：」前缀），主状态保持 SUCCEEDED。 */
    FAILED
}
