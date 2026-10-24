/**
 * 任务相关的后端返回形状与请求体。
 *
 * 字段名与后端 Java record 逐字对齐（TaskResponse / RunResponse / TaskRunRefResponse），
 * 方便对着源码排查。可空字段写成 `| null`，不用 `?` 可选属性。
 */
import type { TASK_RUN_STATUS, TASK_RUN_TYPE } from '@/enums/task'

/** 任务来源：链接提交 / 本站上传。 */
export type TaskInputType = 'URL' | 'UPLOAD'

/** 一次执行的类型。 */
export type TaskRunType = (typeof TASK_RUN_TYPE)[keyof typeof TASK_RUN_TYPE]

/** 一次执行的状态。 */
export type TaskRunStatus = (typeof TASK_RUN_STATUS)[keyof typeof TASK_RUN_STATUS]

/** 一次执行的结果：序号、类型、状态、当前阶段、失败原因、起止时间。 */
export interface RunResponse {
  /** 任务内递增的执行序号，从 1 起。订阅进度流要带它。 */
  runNo: number
  /** 执行类型。 */
  runType: TaskRunType
  /** 目标语种；只有翻译那次有值，其余是 null。 */
  targetLanguage: string | null
  /** 执行状态。 */
  status: TaskRunStatus
  /** 当前阶段名；还没进入第一个阶段时是 null。 */
  currentStage: string | null
  /** 失败原因（中文，面向用户）；没失败时是 null。 */
  errorMessage: string | null
  /** 开始执行时间。 */
  startedAt: string
  /** 终态时间；还在跑时是 null。 */
  finishedAt: string | null
}

/**
 * 任务快照。状态、阶段、失败原因、起止时间都收在 lastRun 里——
 * 后端不再把状态平铺到顶层，本前端跟着改，不再读顶层字段。
 */
export interface TaskResponse {
  /** 对外任务号。 */
  taskNo: string
  /** 输入类型。 */
  inputType: TaskInputType
  /** 来源：链接原文或上传文件名。 */
  sourceUri: string
  /** 创建时间。 */
  createdAt: string
  /** 最近一次执行；从未执行过时是 null。 */
  lastRun: RunResponse | null
}

/**
 * 触发类接口的返回值：任务号 + 本次执行的序号。
 * 提交 / 上传 / 翻译 / 重新生成都回这个形状，409 冲突时也是这个形状。
 */
export interface TaskRunRef {
  /** 对外任务号。 */
  taskNo: string
  /** 本次执行的序号，进度流按它订阅。 */
  runNo: number
}

/** 提交链接的请求体。 */
export interface SubmitTaskRequest {
  /** 媒体链接。 */
  url: string
  /** 初始关键词；字段缺席表示不设关键词。 */
  keywords?: string[]
}

/** 翻译请求体。 */
export interface TranslationRequest {
  /** 源语种。 */
  sourceLanguage: string
  /** 目标语种。 */
  targetLanguage: string
}

/**
 * 重新生成字幕的请求体。
 * keywords 必须是数组：空数组表示清空关键词后重跑，字段缺席（null）表示沿用现有清单。
 * 前端总是显式给数组，不发 null。
 */
export interface RegenerateSubtitlesRequest {
  /** 本次生效的完整关键词清单。 */
  keywords: string[]
}

/** 阶段进度条的状态。 */
export type StageProgressState = 'RUNNING' | 'SUCCEEDED' | 'SKIPPED' | 'FAILED'

/** 一个阶段当前的进度与文案。 */
export interface StageProgress {
  /** 阶段名；后端合成终态阶段时可能是 null。 */
  stage: string | null
  /**
   * 进度百分比。
   * 主管线各阶段发的是**任务级** 0–100（后端按阶段权重算好的区间端点），
   * 不是阶段内的 0–100；重新生成字幕那次复用了清洗阶段，原始值从 70 起。
   */
  progress: number
  /** 这一步的文案。 */
  message: string
  /** 阶段状态。 */
  state: StageProgressState
}

/**
 * 进度流推来的一条事件（后端 TaskRunProgress 的形状）。
 * terminal 由后端算好，前端直接读，不再自己拼状态组合。
 */
export interface TaskRunProgress {
  /** 任务号。 */
  taskNo: string
  /** 本次执行的序号。 */
  runNo: number
  /** 执行类型。 */
  runType: TaskRunType
  /** 执行状态。 */
  status: TaskRunStatus
  /** 当前阶段；还没进入第一个阶段时是 null。 */
  stage: StageProgress | null
  /** 是否已经到终态。 */
  terminal: boolean
  /** 目标语种；只有翻译那次有这个字段。 */
  targetLanguage?: string | null
}
