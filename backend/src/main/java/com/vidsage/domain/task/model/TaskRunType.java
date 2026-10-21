package com.vidsage.domain.task.model;

/**
 * 执行类型：这一条 run 是谁发起的。取值与数据库 {@code task_run.run_type} 列一一对应。
 */
public enum TaskRunType {

    /** 主管线：从媒体获取一路跑到字幕产物。 */
    MAIN,

    /** 重新生成字幕：只重跑清洗，不重跑 ASR、不重抽音频。 */
    RECLEAN,

    /** 翻译：在已有清洗产物上产出译文。 */
    TRANSLATE
}
