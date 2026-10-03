/**
 * 任务域后端契约类型，与 Java record / 枚举逐字段镜像（字段名保持一致，便于对照排查）。
 *
 * 自原 src/api/types.ts 迁出（该文件已删除，2026-10-03 两级类型策略）：
 * src/types/ 只收**跨模块共享**类型，模块私有类型留模块内。
 *
 * 后端无 `{code, data}` 包装：响应体即裸 DTO，语义全在 HTTP 状态码。
 * 时间字段为 ISO-8601 字符串（Spring Boot 对 LocalDateTime 的默认序列化）；
 * 可空字段显式写 `| null`，与后端 null 语义对齐。
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

/** 阶段状态——镜像后端 `StageEvent.StageState`；SKIPPED 表示产物已存在且非空（断点复用跳过） */
export type StageState = 'RUNNING' | 'SUCCEEDED' | 'SKIPPED' | 'FAILED'

/** 阶段进度事件——镜像后端 `StageEvent` */
export interface StageEvent {
  /** 阶段名，如 acquire / extract-audio */
  stage: string
  /** 阶段内进度百分比，取值 0–100 */
  progress: number
  /** 面向用户的中文描述 */
  message: string
  /** 阶段状态 */
  state: StageState
}

/**
 * 任务进度事件（SSE 事件名 progress 的 data）——镜像后端 `TaskProgressMessage`。
 * 终态事件的 stage 可为 null（状态本身就是信息）；主管线事件的 translationStatus 为 null，
 * 翻译事件的主状态恒为 SUCCEEDED，终态判定须同时看两个状态（见 useTaskEvents）。
 */
export interface TaskProgressMessage {
  /** 对外任务号，与订阅端点的 taskNo 一致 */
  taskNo: string
  /** 事件发出时的任务主状态 */
  taskStatus: TaskStatus
  /** 最近阶段事件；终态广播或尚无阶段事件时为 null */
  stage: StageEvent | null
  /** 翻译子状态；从未触发（含全部主管线事件）时为 null，翻译事件携带当前子状态 */
  translationStatus: TranslationStatus | null
}

/** 产物 segments-*.json 数组元素形状（原文段落）——镜像后端 `TranscriptionSegment` */
export interface TranscriptionSegment {
  /** 句子开始时间（毫秒） */
  beginTimeMs: number
  /** 句子结束时间（毫秒） */
  endTimeMs: number
  /** 句子识别文本 */
  text: string
}

/**
 * 产物 segments-*-translate-*.json 数组元素形状（双语段落）——镜像后端 `TranslatedSegment`。
 * 后端按逐条下标与原文段落配对（时间戳逐条一致）；sourceText / translatedText 为 null 时渲染按空串。
 */
export interface TranslatedSegment {
  /** 本句开始时间（毫秒），与原文段落逐条一致 */
  beginTimeMs: number
  /** 本句结束时间（毫秒），与原文段落逐条一致 */
  endTimeMs: number
  /** 清洗后的原文；null 在渲染时视为空串 */
  sourceText: string | null
  /** 译文；等于 sourceText 表示无需翻译或整趟跳过（源语种等于目标语种） */
  translatedText: string | null
}

/** 字幕来源路线——镜像后端 `SubtitleRoute`；与产物文件名里的 kebab token 同源（ASR_CLOUD → asr-cloud） */
export type SubtitleRoute = 'PLATFORM_MANUAL' | 'PLATFORM_AUTO' | 'ASR_LOCAL' | 'ASR_CLOUD'

/**
 * 原文产物文件信封（segments-*.json 落盘形状，raw 与 -clean 同构）——镜像后端 `SubtitleTrack`。
 * 产物文件是信封结构而非裸数组，段落取 `segments` 字段。
 */
export interface SubtitleArtifactFile {
  /** 字幕来源路线（与产物文件名里的 token 同源） */
  route: SubtitleRoute
  /** 识别出的语种；云端实测可能为 null（序列化落 null 而非空串） */
  language: string | null
  /** 逐句段落，时间升序；无语音时为空列表（合法产出，非错误） */
  segments: TranscriptionSegment[]
}

/**
 * 译文产物文件信封（segments-*-translate-*.json 落盘形状）——镜像后端 `TranslatedTrack`。
 */
export interface TranslatedArtifactFile {
  /** 字幕来源路线（与产物文件名里的 token 同源） */
  route: SubtitleRoute
  /** 判定跳过时实际采用的源语种（BCP-47 短码）；两个来源皆空时为空串 */
  sourceLanguage: string
  /** 目标语种（BCP-47 短码），来自配置 */
  targetLanguage: string
  /** 是否没调用 LLM 就产出（源语种等于目标语种时为 true） */
  skipped: boolean
  /** 逐条双语段落，时间升序；无段落时为空列表 */
  segments: TranslatedSegment[]
}
