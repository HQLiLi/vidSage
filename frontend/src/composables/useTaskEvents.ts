import { onScopeDispose, ref } from 'vue'

import type { TaskProgressMessage } from '@/types/task'

/**
 * 连续失败多少次才提示「正在重连」。EventSource 默认重连间隔约 3s，一次网络抖动几秒内
 * 自愈；连败 2 次（约 6s）才提示，避免正常网络抖动闪一下提示条。
 */
const RECONNECT_HINT_AFTER = 2

/**
 * 任务进度 SSE 订阅（GET /api/tasks/{taskNo}/events，事件名 progress，data 为
 * TaskProgressMessage JSON——契约镜像见 @/types/task）。
 *
 * - `latest`：最新一条进度事件（初始 null，重订阅时复位），消费方 watch 它驱动状态机；
 * - `subscribe(taskNo)`：建立连接，重复调用先关旧连接；断线不手动重连——浏览器
 *   EventSource 自动重连，后端连接建立即推 DB 快照，进度无感恢复；
 * - `close()`：主动断开（幂等）；收到终态事件后自动 close；
 * - `reconnecting`：连续重连失败达阈值后为真，供页面显示不遮挡进度的重连提示；
 * - `fatal`：这条流已死（浏览器不再重连，如非 200 / content-type 不符）时为真，
 *   由消费方重拉快照确诊；
 * - 心跳是 SSE 注释行（:ping），不触发 progress 事件；作用域销毁时经 onScopeDispose
 *   自动 close，不留给调用方。
 *
 * 返回类型不具名标注：消费方经调用返回值推断取得（当前无具名消费需求）。
 */
export function useTaskEvents() {
  const latest = ref<TaskProgressMessage | null>(null)
  const reconnecting = ref(false)
  const fatal = ref(false)

  let source: EventSource | null = null
  // 连续失败次数：只用来决定何时置 reconnecting，不做响应式
  let consecutiveErrors = 0

  /**
   * 关闭当前 SSE 连接并复位句柄（幂等，未连接时空操作）。
   *
   * 顺带熄掉「正在重连」提示：连接都没了，这条提示就不成立。复位放这里而不是只放致命分支，
   * 是因为 `close()` 还有别的调用方（store 的 `load()` 开头、作用域销毁）——它们之后如果
   * 没走到 `subscribe()`（例如 `load()` 失败、taskView 为空使 subscribe 早退），提示条会没人收拾。
   *
   * 这里**不复位 `fatal`**：致命分支是先置 `fatal = true` 再调本方法，复位会把刚立起来的状态冲掉。
   */
  function close(): void {
    source?.close()
    source = null
    reconnecting.value = false
    consecutiveErrors = 0
  }

  /**
   * 订阅指定任务的进度事件流：先关旧连接并复位 latest（重订阅不留上一条旧事件，
   * 消费方 watch 不会把陈旧事件当新事件），再建新连接，避免同任务重复订阅或跨任务串线。
   */
  function subscribe(taskNo: string): void {
    close()
    latest.value = null
    // 重订阅是一次全新的连接周期，连接态从零起步
    reconnecting.value = false
    fatal.value = false
    consecutiveErrors = 0
    const es = new EventSource(`/api/tasks/${taskNo}/events`)
    source = es
    es.addEventListener('progress', (event) => {
      // data 即 TaskProgressMessage JSON（后端契约镜像，无本地校验层）；解析失败按
      // 畸形事件忽略该条，保持连接等下一条。
      if (!(event instanceof MessageEvent)) return
      try {
        latest.value = JSON.parse(event.data) as TaskProgressMessage
      } catch {
        // 畸形 JSON：丢弃，不断开
        return
      }
      // 流已经活了：收到事件即视为连接正常，复位重连提示与计数（不必等 onopen）
      consecutiveErrors = 0
      reconnecting.value = false
      if (latest.value?.terminal) close()
    })
    es.onopen = () => {
      // 每次成功（重）连接都会触发：复位计数与提示，避免恢复后提示条残留
      consecutiveErrors = 0
      reconnecting.value = false
    }
    es.onerror = () => {
      // onerror 不带状态码，靠 readyState 分两类：CLOSED = 浏览器 fail 且不再重连
      // （非 200 / content-type 不符），这条流已经死了；CONNECTING = 断线自动重连中
      if (es.readyState === EventSource.CLOSED) {
        // 这条流死了，不会再有 onopen / progress 来收拾；重连提示由 close() 一并熄掉
        fatal.value = true
        close()
        return
      }
      consecutiveErrors += 1
      if (consecutiveErrors >= RECONNECT_HINT_AFTER) reconnecting.value = true
    }
  }

  // 作用域（组件 setup / Pinia store）销毁时收口连接
  onScopeDispose(close)

  return { latest, subscribe, close, reconnecting, fatal }
}
