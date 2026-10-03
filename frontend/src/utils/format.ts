/**
 * 媒体时长与字节体积的格式化纯函数（不依赖任何库）。
 *
 * 职责分野（design §2.1）：这里只管**时长**与**体积**——两者都不是日历时间，
 * 不走 dayjs（`@/lib/day`）：dayjs 没有时长 API，硬套 `dayjs(0).ms(s)` 会在
 * ≥1 小时时静默丢小时位。日历时间（提交/完成时刻、耗时）才用 dayjs。
 */

/**
 * 时长秒数 → mm:ss 时间码。注意这是**时长**口径：分钟位不封顶（3700 秒 → "61:40"），
 * 不会丢小时信息；用于播放器当前时间 / 总时长 / 字幕时间码展示。
 */
export function formatTime(seconds: number): string {
  const s = Math.floor(seconds)
  const m = Math.floor(s / 60)
  return `${String(m).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}

/** 体积单位阶梯（1024 进制）；索引访问已由调用处兜底（noUncheckedIndexedAccess）。 */
const SIZE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const

/**
 * 字节数 → 人类可读体积（1024 进制，B / KB / MB / GB / TB），用于产物清单的体积列。
 * 小于 1 KB 显示整数 B；其余保留一位小数，≥100 时省去小数位。
 */
export function formatBytes(bytes: number): string {
  let value = bytes
  let unitIndex = 0
  while (value >= 1024 && unitIndex < SIZE_UNITS.length - 1) {
    value /= 1024
    unitIndex += 1
  }
  const unit = SIZE_UNITS[unitIndex] ?? 'B'
  if (unitIndex === 0) return `${value} B`
  return `${value.toFixed(value >= 100 ? 0 : 1)} ${unit}`
}
