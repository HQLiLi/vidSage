/**
 * 工作区页面的视图状态枚举。
 *
 * 这些值不来自后端，但会在页面、状态卡与加载逻辑之间比较，所以按 type-safety.md 的
 * 规矩放 enums/：比较处写命名常量，不写裸字面量。不用 TS 的 enum 语法，只用 as const 对象。
 */

/** 页面当前处于哪种状态。 */
export const WORKSPACE_PHASE = {
  LOADING: 'loading',
  NOT_FOUND: 'not-found',
  ERROR: 'error',
  RUNNING: 'running',
  READY: 'ready',
  RUN_FAILED: 'run-failed',
} as const

/** 加载失败的种类：任务不存在（404）与其他错误。 */
export const WORKSPACE_LOAD_ERROR_KIND = {
  NOT_FOUND: 'not-found',
  ERROR: 'error',
} as const
