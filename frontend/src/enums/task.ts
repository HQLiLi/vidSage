/**
 * 后端「一次执行」相关枚举的运行时值。
 *
 * 为什么单独放 enums/：这些值在多处要比较（页面状态判定、进度换算、上传页跳转），
 * 按 type-safety.md 的规矩——有比较消费的值放这里，类型联合由 types/ 用 typeof 派生，
 * 比较处写命名常量而不写裸字面量。不用 TS 的 enum 语法，只用 as const 对象。
 */

/** 一次执行的类型：主管线 / 重新生成字幕 / 翻译。键名与后端 TaskRunType 一致。 */
export const TASK_RUN_TYPE = {
  MAIN: 'MAIN',
  RECLEAN: 'RECLEAN',
  TRANSLATE: 'TRANSLATE',
} as const

/**
 * 一次执行的状态。后端只有这四种，没有 PENDING：
 * 任务一创建就有一条 RUNNING 的 run，排队不是一种状态。
 */
export const TASK_RUN_STATUS = {
  RUNNING: 'RUNNING',
  SUCCEEDED: 'SUCCEEDED',
  FAILED: 'FAILED',
  INTERRUPTED: 'INTERRUPTED',
} as const
