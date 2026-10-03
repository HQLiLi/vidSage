import { onScopeDispose, ref } from 'vue'
import type { TaskProgressMessage } from '@/types/task'

/**
 * 终态判定：与后端 TaskController.isTerminal / TaskSseHub.send 同一口径——
 * 主状态终态（SUCCEEDED / FAILED）**且**翻译子状态不在 RUNNING，任务才不再有后续事件。
 * 翻译事件的 taskStatus 恒为主状态 SUCCEEDED，只看主状态会把翻译进行中的连接提前收掉。
 */
function isTerminalEvent(event: TaskProgressMessage): boolean {
  const mainTerminal = event.taskStatus === 'SUCCEEDED' || event.taskStatus === 'FAILED'
  return mainTerminal && event.translationStatus !== 'RUNNING'
}

/**
 * 任务进度 SSE 订阅（GET /api/tasks/{taskNo}/events，事件名 progress，data 为
 * TaskProgressMessage JSON——契约镜像见 @/types/task）。
 *
 * - `latest`：最新一条进度事件（初始 null，重订阅时复位），消费方 watch 它驱动状态机；
 * - `subscribe(taskNo)`：建立连接，重复调用先关旧连接；断线不手动重连——浏览器
 *   EventSource 自动重连，后端连接建立即推 DB 快照，进度无感恢复；
 * - `close()`：主动断开（幂等）；收到终态事件后自动 close；
 * - 心跳是 SSE 注释行（:ping），不触发 progress 事件；作用域销毁时经 onScopeDispose
 *   自动 close，不留给调用方。
 *
 * 返回类型不就地标注：由文件尾的 `UseTaskEvents` 别名（ReturnType）承载，避免循环引用。
 */
export function useTaskEvents() {
  const latest = ref<TaskProgressMessage | null>(null)

  let source: EventSource | null = null

  /** 关闭当前 SSE 连接并复位句柄（幂等，未连接时空操作）。 */
  function close(): void {
    source?.close()
    source = null
  }

  /**
   * 订阅指定任务的进度事件流：先关旧连接并复位 latest（重订阅不留上一条旧事件，
   * 消费方 watch 不会把陈旧事件当新事件），再建新连接，避免同任务重复订阅或跨任务串线。
   */
  function subscribe(taskNo: string): void {
    close()
    latest.value = null
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
      if (latest.value && isTerminalEvent(latest.value)) close()
    })
  }

  // 作用域（组件 setup / Pinia store）销毁时收口连接
  onScopeDispose(close)

  return { latest, subscribe, close }
}

/** useTaskEvents 的返回类型：消费方以本类型引用订阅器（模块私有派生类型，不迁全局）。 */
export type UseTaskEvents = ReturnType<typeof useTaskEvents>
