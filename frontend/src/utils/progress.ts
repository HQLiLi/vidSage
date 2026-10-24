/**
 * 进度事件的解析与换算（纯函数）。
 *
 * 后端给的三种进度口径不一样：
 * - 主管线（MAIN）发的是任务级 0–100，按阶段权重算好的，直接显示。
 * - 重新生成字幕（RECLEAN）复用了清洗阶段，原始值是 70→100，要归一到 0–100，
 *   跟翻译的观感一致。
 * - 翻译（TRANSLATE）是本次执行自己的 0–100，直接显示。
 */
import { STAGE_PROGRESS_STATE, TASK_RUN_STATUS, TASK_RUN_TYPE } from '@/enums/task'
import type {
  StageProgress,
  StageProgressState,
  TaskRunProgress,
  TaskRunStatus,
  TaskRunType,
} from '@/types/task'

/**
 * 清洗阶段在主管线里的起点。
 * 镜像后端 SubtitleStageName 按权重（5/42/42/49/7/60）累加出来的区间端点；
 * 后端改了权重表，这里要跟着改——只影响进度条的观感，不影响别的。
 */
export const RECLEAN_STAGE_BASE = 70

/** 清洗阶段在主管线里的终点。 */
export const RECLEAN_STAGE_END = 100

/** 换算出来的进度：百分比 0–100 与一句阶段文案。 */
export interface RunProgressView {
  /** 百分比，0–100。 */
  percent: number
  /** 阶段文案；没有阶段时是空串。 */
  message: string
}

/**
 * 把一条进度事件换算成页面要显示的百分比与文案。
 *
 * 没有事件、或事件里还没有阶段信息时，百分比是 0、文案是空串。
 */
export function normalizeRunProgress(
  runType: TaskRunType,
  raw: TaskRunProgress | null,
): RunProgressView {
  const stage = raw?.stage
  if (!stage) return { percent: 0, message: '' }
  const percent =
    runType === TASK_RUN_TYPE.RECLEAN ? rescaleReclean(stage.progress) : stage.progress
  return { percent: clamp(percent), message: stage.message }
}

/**
 * 新来的一条进度是不是比当前显示的更小。
 *
 * 重连时后端会补推一条快照，里面的进度固定是 0；照收的话进度条会往回跳，
 * 阶段文案也会被「任务执行中」这种粗话冲掉。所以判定回退时整条丢掉，不是只丢百分比。
 */
export function hasRunProgressRegressed(
  previous: TaskRunProgress,
  next: TaskRunProgress,
): boolean {
  const shown = normalizeRunProgress(previous.runType, previous)
  const incoming = normalizeRunProgress(next.runType, next)
  return incoming.percent < shown.percent
}

/** 把清洗阶段的 70–100 线性映射到 0–100。 */
function rescaleReclean(raw: number): number {
  const span = RECLEAN_STAGE_END - RECLEAN_STAGE_BASE
  if (span <= 0) return 0
  return ((raw - RECLEAN_STAGE_BASE) * 100) / span
}

/** 钳制到 0–100。 */
function clamp(value: number): number {
  if (!Number.isFinite(value)) return 0
  return Math.min(100, Math.max(0, Math.round(value)))
}

/**
 * 把一条进度流事件里的 data 解析成进度对象。
 *
 * 形状不对就返回 null，调用方丢这一条即可——一条坏数据不该把整条流掐了。
 * `stage` 是 null 或整个字段缺席都按「还没有阶段」处理。
 */
export function parseRunProgress(raw: string): TaskRunProgress | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }
  if (!isRecord(parsed)) return null

  const { taskNo, runNo, runType, status, stage, terminal, targetLanguage } = parsed
  if (typeof taskNo !== 'string' || typeof runNo !== 'number') return null
  if (!isRunType(runType) || !isRunStatus(status)) return null
  if (typeof terminal !== 'boolean') return null
  const parsedStage = parseStage(stage ?? null)
  if (stage !== null && stage !== undefined && parsedStage === null) return null

  const progress: TaskRunProgress = { taskNo, runNo, runType, status, stage: parsedStage, terminal }
  // 只有翻译那条流带这个字段；其余是字段缺席，不是 null
  if (typeof targetLanguage === 'string' || targetLanguage === null) {
    progress.targetLanguage = targetLanguage
  }
  return progress
}

/** 判定未知值是不是一个普通对象。 */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** 判定是不是已知的执行类型。 */
function isRunType(value: unknown): value is TaskRunType {
  return Object.values(TASK_RUN_TYPE).some((type) => type === value)
}

/** 判定是不是已知的执行状态。 */
function isRunStatus(value: unknown): value is TaskRunStatus {
  return Object.values(TASK_RUN_STATUS).some((status) => status === value)
}

/** 判定是不是已知的阶段状态。 */
function isStageState(value: unknown): value is StageProgressState {
  return Object.values(STAGE_PROGRESS_STATE).some((state) => state === value)
}

/** 解析阶段进度；字段不完整就当作没有阶段。 */
function parseStage(raw: unknown): StageProgress | null {
  if (raw === null) return null
  if (!isRecord(raw)) return null
  const { stage, progress, message, state } = raw
  if (typeof progress !== 'number' || typeof message !== 'string') return null
  if (!isStageState(state)) return null
  return {
    stage: typeof stage === 'string' ? stage : null,
    progress,
    message,
    state,
  }
}
