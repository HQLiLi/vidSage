/**
 * 任务域枚举常量（运行时值）：`as const` 对象，键与后端 Java 枚举常量同名（契约镜像）。
 *
 * 这里只承载**值**，不含类型——类型联合在 @/types/task 经 `import type` + `typeof` 派生
 * （types/ 保持纯类型、构建期整体擦除）。比较处用本文件的命名常量，不写裸字面量；
 * 无比较消费的枚举不在这里建对象。
 */

/** 任务主状态值：PENDING（排队中）/ RUNNING（执行中）/ SUCCEEDED（成功）/ FAILED（失败）——镜像后端 `TaskStatus` 枚举常量 */
export const TASK_STATUS = {
  PENDING: 'PENDING',
  RUNNING: 'RUNNING',
  SUCCEEDED: 'SUCCEEDED',
  FAILED: 'FAILED',
} as const

/** 翻译子状态值：RUNNING（执行中）/ SUCCEEDED（成功）/ FAILED（失败）——镜像后端 `TranslationStatus` 枚举常量 */
export const TRANSLATION_STATUS = {
  RUNNING: 'RUNNING',
  SUCCEEDED: 'SUCCEEDED',
  FAILED: 'FAILED',
} as const
