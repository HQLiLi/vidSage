/**
 * 一条 run 的进度流订阅。
 *
 * 后端一条 run 一条流。浏览器自带的自动重连分不清「run 已经跑完（204）」和
 * 「网络断了」，所以这里把自带的关掉，改自己退避重试：每次断线先问业务层
 * 要不要重连（业务层会顺带拉一次快照，确认这条 run 到底是不是已经结束），
 * 最多重试三次，三次都不通就让业务层给手动入口。
 */
import { onScopeDispose, ref } from 'vue'
import type { Ref } from 'vue'

import { RUN_STREAM_STATE } from '@/enums/workspace'
import { parseRunProgress } from '@/utils/progress'
import type { TaskRunProgress } from '@/types/task'

/** 第 1、2、3 次重连前分别等多久。 */
const RECONNECT_DELAYS_MS = [1000, 2000, 4000] as const

/** 流的状态；不含页面层的 idle。 */
export type RunStreamState = Exclude<
  (typeof RUN_STREAM_STATE)[keyof typeof RUN_STREAM_STATE],
  typeof RUN_STREAM_STATE.IDLE
>

/** 业务层要接的四个回调。 */
interface RunEventHandlers {
  /** 收到一条还没到终态的进度。 */
  onProgress(progress: TaskRunProgress): void
  /** 这条 run 已结束；内部已经关流。 */
  onTerminal(progress: TaskRunProgress): void
  /** 断线后问业务层要不要退避重连；业务层在这里拉快照确诊。 */
  shouldReconnect(): Promise<boolean>
  /** 重试次数用尽。 */
  onRetryExhausted(): void
}

/** 订阅一条 run 的进度流，并管好重连、代际与清理。 */
export function useRunEvents(handlers: RunEventHandlers): {
  state: Ref<RunStreamState>
  start: (taskNo: string, runNo: number) => void
  close: () => void
} {
  /** 流当前处在哪个状态。 */
  const state = ref<RunStreamState>(RUN_STREAM_STATE.CLOSED)

  /** 当前连接；null = 没连。 */
  let source: EventSource | null = null
  /** 退避重连的定时器。 */
  let retryTimer: ReturnType<typeof setTimeout> | null = null
  /**
   * 代际标记。每次开始新连接或主动关流都加一；
   * 回调触发时比对建立连接时抓下的值，不一致就丢掉——防止旧流的迟到事件串到新 run 上。
   */
  let generation = 0
  /** 已经重连了几次。 */
  let retryCount = 0
  /** 这次要跟的 run；重连时复用。 */
  let targetTaskNo = ''
  let targetRunNo = 0

  /** 开始跟一条 run：先关掉旧连接，再建新连接。 */
  function start(taskNo: string, runNo: number): void {
    close()
    targetTaskNo = taskNo
    targetRunNo = runNo
    retryCount = 0
    connect()
  }

  /** 建一条新连接。 */
  function connect(): void {
    const gen = generation
    state.value = RUN_STREAM_STATE.CONNECTING

    let next: EventSource
    try {
      // EventSource 吃不到 axios 的 baseURL，路径要写全
      next = new EventSource(
        `/api/tasks/${encodeURIComponent(targetTaskNo)}/runs/${targetRunNo}/events`,
      )
    } catch {
      state.value = RUN_STREAM_STATE.CLOSED
      return
    }
    source = next

    next.addEventListener('progress', (event) => {
      if (gen !== generation) return
      if (!(event instanceof MessageEvent)) return
      const progress = parseRunProgress(event.data)
      // 解析不出来就丢这一条，不断流
      if (progress === null) return
      if (progress.taskNo !== targetTaskNo || progress.runNo !== targetRunNo) return

      // 能收到事件说明流是活的，重试计数从头算
      retryCount = 0

      if (progress.terminal) {
        close()
        handlers.onTerminal(progress)
        return
      }
      state.value = RUN_STREAM_STATE.LIVE
      handlers.onProgress(progress)
    })

    next.onerror = () => {
      if (gen !== generation) return
      // 先关掉：浏览器默认会自动重连，那个我们不要
      dropSource()
      void reconnect(gen)
    }
  }

  /** 断线之后：问业务层要不要重连，要就按退避时间排下一次。 */
  async function reconnect(gen: number): Promise<void> {
    const shouldRetry = await handlers.shouldReconnect()
    if (gen !== generation) return
    if (!shouldRetry) {
      state.value = RUN_STREAM_STATE.CLOSED
      return
    }
    if (retryCount >= RECONNECT_DELAYS_MS.length) {
      state.value = RUN_STREAM_STATE.CLOSED
      handlers.onRetryExhausted()
      return
    }
    const delay = RECONNECT_DELAYS_MS[retryCount] ?? 0
    retryCount += 1
    state.value = RUN_STREAM_STATE.RECONNECTING
    retryTimer = setTimeout(() => {
      retryTimer = null
      if (gen !== generation) return
      connect()
    }, delay)
  }

  /** 关掉当前连接，但不动代际（给 onerror 用）。 */
  function dropSource(): void {
    if (source === null) return
    source.close()
    source = null
  }

  /** 主动关流：幂等，清定时器、关连接、作废所有在途回调。 */
  function close(): void {
    generation += 1
    if (retryTimer !== null) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
    dropSource()
    state.value = RUN_STREAM_STATE.CLOSED
  }

  onScopeDispose(close)

  return { state, start, close }
}
