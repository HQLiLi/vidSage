/**
 * 进度百分比的换算（纯函数）。
 *
 * 后端给的三种进度口径不一样：
 * - 主管线（MAIN）发的是任务级 0–100，按阶段权重算好的，直接显示。
 * - 重新生成字幕（RECLEAN）复用了清洗阶段，原始值是 70→100，要归一到 0–100，
 *   跟翻译的观感一致。
 * - 翻译（TRANSLATE）是本次执行自己的 0–100，直接显示。
 */
import { TASK_RUN_TYPE } from '@/enums/task'
import type { TaskRunProgress, TaskRunType } from '@/types/task'

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
  const percent = runType === TASK_RUN_TYPE.RECLEAN ? rescaleReclean(stage.progress) : stage.progress
  return { percent: clamp(percent), message: stage.message }
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
