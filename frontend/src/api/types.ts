/**
 * 后端接口契约类型，与 Java record / 枚举逐字段镜像（字段名保持一致，便于对照排查）。
 *
 * 后端无 `{code, data}` 包装：响应体即裸 DTO，语义全在 HTTP 状态码。
 * 时间字段为 ISO-8601 字符串（Spring Boot 对 LocalDateTime 的默认序列化）。
 */

/** 输入类型：URL（链接提交）/ UPLOAD（本站文件上传）——镜像后端 `TaskInputType` */
export type TaskInputType = 'URL' | 'UPLOAD'

/** 任务状态：PENDING（排队中）/ RUNNING（执行中）/ SUCCEEDED（成功）/ FAILED（失败）——镜像后端 `TaskStatus` */
export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

/** 翻译子状态——镜像后端 `TranslationStatus`；`null` 表示从未触发（主任务成功后按需触发翻译） */
export type TranslationStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED'

/** 任务产物文件清单项——镜像后端 `TaskArtifact` */
export interface TaskArtifact {
  /** 产物文件名（不含路径），可直接用于产物下载端点 */
  name: string
  /** 文件字节数 */
  sizeBytes: number
}

/** 任务视图——镜像后端 `TaskView` */
export interface TaskView {
  /** 对外任务号，后续查询 / 订阅进度都凭它 */
  taskNo: string
  inputType: TaskInputType
  /** 来源：URL 原文或上传文件原始名 */
  sourceUri: string
  status: TaskStatus
  translationStatus: TranslationStatus | null
  /** 最近阶段名；未开始时为 null */
  currentStage: string | null
  /** 终态错误信息（中文）；无错误时为 null */
  errorMessage: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  /** 产物清单（*.srt 与 segments-*.json）；尚无产物时为空表 */
  artifacts: TaskArtifact[]
}

/** 提交 URL 任务请求体——镜像后端 `SubmitUrlRequest` */
export interface SubmitUrlRequest {
  /** 公网媒体页面地址（如 B 站 / YouTube 链接） */
  url: string
}

/** 同 URL / 同内容冲突（409）的响应体——镜像后端 `TaskConflictResponse` */
export interface TaskConflictResponse {
  /** 错误说明 */
  error: string
  /** 既有任务视图，可直接拿它的 taskNo 继续跟进 */
  existingTask: TaskView
}

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
