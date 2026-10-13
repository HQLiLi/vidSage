/**
 * 媒体域后端契约类型，与 Java record / 枚举逐字段镜像（字段名保持一致，便于对照排查）。
 *
 * `src/types/` 只收**跨模块共享**类型，模块私有类型留模块内。
 */

/** 单个外部工具的诊断报告——镜像后端 `ExternalToolReport` */
export interface ExternalToolReport {
  /** 工具名（yt-dlp / ffmpeg / ffprobe） */
  toolName: string
  /** 是否找到可用二进制 */
  available: boolean
  /** 解析到的版本号；不可用或版本解析失败时为 null */
  version: string | null
  /** 解析到的绝对路径；不可用时为 null */
  path: string | null
  /** 提示语：不可用时为「缺什么 + 怎么补」，版本过期时为新鲜度告警，其余为 null */
  hint: string | null
}
