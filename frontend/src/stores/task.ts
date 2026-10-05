import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'

import { fetchArtifactJson, getTask, translate as translateTask } from '@/api/tasks'
import { ApiError } from '@/api/request'
import { useTaskEvents } from '@/composables/useTaskEvents'
import { TASK_STATUS, TRANSLATION_STATUS } from '@/enums/task'
import { buildCues, pickSourceArtifact, pickTranslatedArtifact } from '@/utils/transcript'
import type {
  SubtitleArtifactFile,
  TaskProgressMessage,
  TaskView,
  TranslatedArtifactFile,
} from '@/types/task'
import type { TranscriptCue } from '@/types/transcript'

/**
 * 工作区加载失败态：404（任务不存在）与其他错误分列，工作区壳据此渲染不同引导卡。
 * 与 store 同文件（type-safety.md：store 相关类型随 store）；仅 store 内部使用不导出，
 * 视图侧经 loadError 的推断类型取得形状。
 */
type TaskLoadError = { kind: 'not-found' } | { kind: 'error'; message: string }

/**
 * 工作区任务 store：工作区页（壳 / header / 媒体面板 / 字幕稿）共用的唯一任务状态源。
 *
 * 数据流（design §1.1）：load() 拉一次 GET 快照（终态直接建 cues）→ subscribe() 开 SSE
 * → progress 事件驱动进度态 → 终态（主状态终态且翻译非 RUNNING）静默重拉快照
 * （errorMessage / artifacts / finishedAt 只在 GET 里有）→ 主状态 SUCCEEDED 时拉产物建 cues。
 * 约定：加载中 = taskView 与 loadError 均为 null，壳页据此渲染 loading 态。
 */
export const useTaskStore = defineStore('task', () => {
  /** 当前任务视图（GET 快照 + 终态静默刷新）；null = 尚未加载成功。 */
  const taskView = ref<TaskView | null>(null)
  /** SSE 最新一条进度事件（含连接建立时后端推的 DB 快照事件）；null = 尚未收到。 */
  const progress = ref<TaskProgressMessage | null>(null)
  /** 字幕稿渲染模型：原文 + 可选译文按行合并；由产物 JSON 构建，无产物时为空表。 */
  const cues = ref<TranscriptCue[]>([])
  /** 加载失败态：区分 404 与其他错误；null = 无失败。 */
  const loadError = ref<TaskLoadError | null>(null)

  // SSE 订阅器随 store 作用域存活，store 销毁（onScopeDispose）时自动断连
  const events = useTaskEvents()

  /** 任务主状态：SSE 事件优先（比 GET 快照新鲜），没有事件时退回快照。 */
  const status = computed(() => progress.value?.taskStatus ?? taskView.value?.status ?? null)

  /** 任务是否在跑（PENDING / RUNNING）——进度卡态。 */
  const isRunning = computed(
    () => status.value === TASK_STATUS.PENDING || status.value === TASK_STATUS.RUNNING,
  )

  /** 任务是否成功——就绪双栏态。 */
  const isSucceeded = computed(() => status.value === TASK_STATUS.SUCCEEDED)

  /** 任务是否失败——失败卡态。 */
  const isFailed = computed(() => status.value === TASK_STATUS.FAILED)

  /** 翻译是否进行中（SSE 事件优先、退回快照）——「翻译」按钮禁用依据。 */
  const isTranslating = computed(
    () =>
      (progress.value?.translationStatus ?? taskView.value?.translationStatus ?? null) ===
      TRANSLATION_STATUS.RUNNING,
  )

  /** 是否已有翻译产物（决定字幕稿双语行与翻译按钮可见性）。 */
  const hasTranslation = computed(() =>
    (taskView.value?.artifacts ?? []).some((a) => a.name.includes('-translate-')),
  )

  /**
   * 加载任务快照（GET /tasks/{taskNo}）：先重置既有状态；快照已是 SUCCEEDED 时
   * 直接拉产物建 cues。404 记为 not-found（任务不存在卡），其余错误记通用失败
   * （文案取归一化 ApiError 的 message，见 request.ts 拦截器）。
   */
  async function load(taskNo: string): Promise<void> {
    events.close()
    progress.value = null
    cues.value = []
    taskView.value = null
    loadError.value = null
    try {
      const view = await getTask(taskNo)
      taskView.value = view
      if (view.status === TASK_STATUS.SUCCEEDED) {
        // 产物 JSON 瞬时失败只静默跳过（不引 console）：任务本身已成功，
        // 不能把整页标成 loadError；下次进入页面经快照恢复
        await fetchCues().catch(() => undefined)
      }
    } catch (error) {
      // 拦截器已把错误归一为 ApiError：404 记任务不存在卡，其余读归一化文案；
      // 非 ApiError（理论上不出现）退回原兜底文案
      const err = error instanceof ApiError ? error : null
      loadError.value =
        err?.status === 404
          ? { kind: 'not-found' }
          : { kind: 'error', message: err?.message ?? '加载失败，请稍后重试' }
    }
  }

  /**
   * 订阅当前任务的 SSE 进度（useTaskEvents）：事件写入 progress；终态事件由
   * watchProgress 收口后续（静默刷新快照、重建 cues），连接本身的自动关闭在
   * composable 内完成。未加载成功（taskView 为空）时不订阅。
   */
  function subscribe(): void {
    const taskNo = taskView.value?.taskNo
    if (!taskNo) return
    events.subscribe(taskNo)
  }

  /**
   * 触发按需翻译（POST /translate，202）：翻译进度由 SSE 事件流的 translationStatus
   * 推送，完成后 watchProgress 自动重建双语 cues。主任务终态时原 SSE 连接已被收口
   * （useTaskEvents 终态即 close，后端 TaskSseHub 同步断开），故 202 更新本地视图后
   * 必须重开订阅：翻译进行中则事件续推；翻译已跑完则新连接的建立快照即
   * SUCCEEDED+翻译终态，经 latest watch 走 refreshAfterTerminal 重建双语 cues。
   * 请求失败（主任务未成功 400 / 任务不存在 404）原样抛出，由调用方内联展示。
   */
  async function translate(): Promise<void> {
    const taskNo = taskView.value?.taskNo
    if (!taskNo) return
    // 用 202 返回的视图即刻更新本地：translationStatus=RUNNING 立即反映到按钮态
    taskView.value = await translateTask(taskNo)
    // 重开 SSE（路径与工作区 onMounted 的 loadThenSubscribe 相同）：无订阅则翻译事件无入口
    subscribe()
  }

  /**
   * 拉取产物并构建字幕稿：原文取 segments-*.json（-clean 优先），译文取
   * segments-*-translate-*.json（存在才拉），经 pickSourceArtifact / buildCues 合并。
   * 产物文件是信封结构（镜像后端 SubtitleTrack / TranslatedTrack），段落取 `segments`
   * 字段；segments 缺失按空数组兜底（防御畸形产物，不扩散到渲染层）。
   * 无原文产物时 cues 置空（空列表是合法产出，不视为错误）。
   */
  async function fetchCues(): Promise<void> {
    const view = taskView.value
    if (!view) return
    const sourceArtifact = pickSourceArtifact(view.artifacts)
    if (!sourceArtifact) {
      cues.value = []
      return
    }
    const source =
      (await fetchArtifactJson<SubtitleArtifactFile>(view.taskNo, sourceArtifact.name)).segments ??
      []
    const translatedArtifact = pickTranslatedArtifact(view.artifacts)
    const translated = translatedArtifact
      ? ((await fetchArtifactJson<TranslatedArtifactFile>(view.taskNo, translatedArtifact.name))
          .segments ?? [])
      : null
    cues.value = buildCues(source, translated)
  }

  /**
   * 终态后的静默刷新：重拉 GET 快照（errorMessage / artifacts / finishedAt 只在快照里），
   * 主状态 SUCCEEDED 时重建字幕（翻译完成即带上译文行）。刷新失败不阻塞界面——
   * SSE 已确认终态，下次进入页面经 GET 快照恢复。
   */
  async function refreshAfterTerminal(): Promise<void> {
    const taskNo = taskView.value?.taskNo
    if (!taskNo) return
    try {
      const view = await getTask(taskNo)
      taskView.value = view
      if (view.status === TASK_STATUS.SUCCEEDED) await fetchCues()
    } catch {
      // 快照刷新失败：保持当前态，不弹错（终态本身已由 SSE 确认）
    }
  }

  // 终态判定与 useTaskEvents 同口径：主状态终态且翻译非 RUNNING 才有收口动作
  watch(events.latest, (message) => {
    if (!message) return
    // 串台防御：只处理当前任务的事件
    if (message.taskNo !== taskView.value?.taskNo) return
    progress.value = message
    const terminal =
      message.taskStatus === TASK_STATUS.SUCCEEDED || message.taskStatus === TASK_STATUS.FAILED
    if (!terminal || message.translationStatus === TRANSLATION_STATUS.RUNNING) return
    void refreshAfterTerminal()
  })

  return {
    // state
    taskView,
    progress,
    cues,
    loadError,
    // computed
    isRunning,
    isSucceeded,
    isFailed,
    isTranslating,
    hasTranslation,
    // actions
    load,
    subscribe,
    translate,
    fetchCues,
  }
})
