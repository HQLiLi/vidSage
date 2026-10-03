/**
 * dayjs 全项目统一入口（模式同 lib/gsap.ts）。
 *
 * 约束：
 * - 组件一律 `import dayjs from '@/lib/day'`，**禁止直接 `from 'dayjs'`**——
 *   locale 与插件的统一配置只在本文件做一次，所有消费者自动生效。
 * - 当前配置：zh-cn locale（中文月份名 / 相对时间表述）+ relativeTime 插件
 *   （「x 分钟前」等相对时长表述，工作区进度卡用）。
 * - 职责分野（design §2.1）：本入口只管**日历时间**（提交/完成时刻、耗时）；
 *   媒体时长 mm:ss 走 `@/utils/format` 的纯函数 formatTime，与本文件无关。
 */
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import relativeTime from 'dayjs/plugin/relativeTime'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

export default dayjs
