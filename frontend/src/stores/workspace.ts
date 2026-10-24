import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { listArtifacts } from '@/api/artifacts'
import { getLanguages } from '@/api/languages'
import { ApiError } from '@/api/request'
import { getTask, regenerateSubtitles, translateTask } from '@/api/tasks'
import { useRunEvents } from '@/composables/useRunEvents'
import { ARTIFACT_TYPE } from '@/enums/subtitle'
import { TASK_RUN_STATUS, TASK_RUN_TYPE } from '@/enums/task'
import { RUN_STREAM_STATE, WORKSPACE_LOAD_ERROR_KIND, WORKSPACE_PHASE } from '@/enums/workspace'
import { useWorkspaceKeywordsStore } from '@/stores/workspaceKeywords'
import { useWorkspaceSubtitlesStore } from '@/stores/workspaceSubtitles'
import { pickArtifact } from '@/utils/artifacts'
import { normalizeLanguageCode } from '@/utils/language'
import { hasRunProgressRegressed, normalizeRunProgress } from '@/utils/progress'
import type { ArtifactResponse, LanguageOption } from '@/types/subtitle'
import type {
  RunResponse,
  TaskResponse,
  TaskRunProgress,
  TaskRunRef,
  TaskRunStatus,
  TaskRunType,
} from '@/types/task'

/**
 * 工作区加载失败态：任务不存在（404）与其他错误分列，页面据此渲染不同的引导卡。
 */
export type WorkspaceLoadError =
  | { kind: typeof WORKSPACE_LOAD_ERROR_KIND.NOT_FOUND }
  | { kind: typeof WORKSPACE_LOAD_ERROR_KIND.ERROR; message: string }

/** 页面当前处于哪种状态。 */
export type WorkspacePhase = (typeof WORKSPACE_PHASE)[keyof typeof WORKSPACE_PHASE]

/** 引导卡能显示的形态；就绪态与运行中都走工作区壳，不进状态卡。 */
export type WorkspaceStatusKind = Exclude<
  WorkspacePhase,
  typeof WORKSPACE_PHASE.READY | typeof WORKSPACE_PHASE.RUNNING
>

/** 页面看到的进度流状态：没有要跟的 run 时是 idle，其余照抄 composable。 */
export type WorkspaceStreamState = (typeof RUN_STREAM_STATE)[keyof typeof RUN_STREAM_STATE]

/**
 * 工作区的任务域：快照、产物清单、语种与页面状态，外加整套加载的编排。
 *
 * 字幕内容与关键词各在自己的 store 里（`workspaceSubtitles` / `workspaceKeywords`），
 * 这里只负责「先拉什么、失败了算谁的」，拉到手后交给对应的域。
 * 进行中的 run 也归这里跟：订阅进度流、收口后刷新，以及触发重新生成与翻译。
 */
export const useWorkspaceStore = defineStore('workspace', () => {
  const subtitles = useWorkspaceSubtitlesStore()
  const keywordStore = useWorkspaceKeywordsStore()

  /** 当前任务号。 */
  const taskNo = ref('')
  /** 任务快照；null = 还没加载成功。 */
  const snapshot = ref<TaskResponse | null>(null)
  /** 加载失败态；null = 没失败。 */
  const loadError = ref<WorkspaceLoadError | null>(null)
  /** 产物清单。 */
  const artifacts = ref<ArtifactResponse[]>([])
  /** 可选语种清单。 */
  const languages = ref<LanguageOption[]>([])
  /** 选中的源语种（规范码）；空串 = 没认出来。 */
  const selectedSourceLanguage = ref('')
  /** 选中的目标语种（规范码）。 */
  const selectedTargetLanguage = ref('en')

  /** 当前跟的 run 序号；null = 没有要跟的 run。 */
  const activeRunNo = ref<number | null>(null)
  /** 最近一条进度事件；换 run 或收口时清空。 */
  const runProgress = ref<TaskRunProgress | null>(null)
  /** 触发请求已经发出、还没拿到 run 引用；借它挡住连点。 */
  const triggering = ref(false)
  /** 连接中断或触发被拒的提示；null = 没出过问题。 */
  const streamError = ref<string | null>(null)
  /**
   * 「重生成把原译文作废了」的提示开关。
   * 它只管提示文案；译文能不能用一律看产物清单里还有没有 TRANSLATED。
   */
  const translationInvalidated = ref(false)

  /** 加载的代际标记：切任务时靠它把上一次还没回来的请求作废。 */
  let loadToken = 0

  /** 进度流的订阅器：只管连接与重试，收口逻辑在下面几个回调里。 */
  const runEvents = useRunEvents({
    onProgress: applyProgress,
    onTerminal: handleTerminal,
    shouldReconnect,
    onRetryExhausted: handleRetryExhausted,
  })

  /** 最近一次执行；从未执行过时是 null。 */
  const lastRun = computed(() => snapshot.value?.lastRun ?? null)

  /**
   * 页面状态。
   *
   * 判据是「有没有字幕可看」：只要清洗后的字幕在，页面就是 ready——
   * 翻译失败、重新生成失败时字幕还好好的，把整页换成错误卡等于把能看的内容藏起来。
   * 没有字幕时才按最近一次执行分流。
   */
  const phase = computed<WorkspacePhase>(() => {
    if (loadError.value?.kind === WORKSPACE_LOAD_ERROR_KIND.NOT_FOUND)
      return WORKSPACE_PHASE.NOT_FOUND
    if (loadError.value) return WORKSPACE_PHASE.ERROR
    if (snapshot.value === null) return WORKSPACE_PHASE.LOADING
    if (subtitles.cleanedTrack !== null) return WORKSPACE_PHASE.READY
    const run = snapshot.value.lastRun
    if (run === null) return WORKSPACE_PHASE.ERROR
    if (run.status === TASK_RUN_STATUS.RUNNING) return WORKSPACE_PHASE.RUNNING
    if (run.status === TASK_RUN_STATUS.SUCCEEDED) return WORKSPACE_PHASE.ERROR
    return WORKSPACE_PHASE.RUN_FAILED
  })

  /** 页面看到的流状态：没有要跟的 run 时是 idle，其余照抄 composable。 */
  const streamState = computed<WorkspaceStreamState>(() => {
    if (triggering.value) return RUN_STREAM_STATE.CONNECTING
    if (activeRunNo.value === null) return RUN_STREAM_STATE.IDLE
    return runEvents.state.value
  })

  /** 当前这次 run 的类型；还没收到事件时退回快照里的 lastRun。 */
  const activeRunType = computed<TaskRunType | null>(
    () => runProgress.value?.runType ?? lastRun.value?.runType ?? null,
  )

  /** 进度条要显示的百分比与阶段文案。 */
  const runProgressView = computed(() => {
    const type = activeRunType.value
    if (type === null) return { percent: 0, message: '' }
    return normalizeRunProgress(type, runProgress.value)
  })

  /** 归一后的进度百分比，0–100。 */
  const runPercent = computed(() => runProgressView.value.percent)
  /** 当前阶段的文案；没有阶段时是空串。 */
  const runMessage = computed(() => runProgressView.value.message)

  /**
   * 这个任务现在有没有一次执行在跑——不只是「本页在跟流」。
   * 后端对同一任务只允许一条 run，所以翻译期间也不让改关键词，免得只换来一个 409。
   */
  const isBusy = computed(() => {
    if (triggering.value) return true
    if (lastRun.value?.status === TASK_RUN_STATUS.RUNNING) return true
    const state = streamState.value
    return (
      state === RUN_STREAM_STATE.CONNECTING ||
      state === RUN_STREAM_STATE.LIVE ||
      state === RUN_STREAM_STATE.RECONNECTING
    )
  })

  /**
   * 打开一个任务：清掉旧状态，重头加载；最近一次执行还在跑就顺手订上进度流。
   */
  async function open(nextTaskNo: string): Promise<void> {
    reset()
    taskNo.value = nextTaskNo
    await loadAll()
    // 切任务后迟到的加载不作数，别给上一个任务建流
    if (taskNo.value !== nextTaskNo) return
    resumeRunIfNeeded()
  }

  /**
   * 重新加载当前任务（错误卡的「重试」、run 收口后的刷新都走这里）。
   * `reloadKeywords` 传 false 时不动关键词清单——翻译收口别把用户本地还没提交的编辑冲掉。
   */
  async function refresh(options: { reloadKeywords?: boolean } = {}): Promise<void> {
    await loadAll(options)
  }

  /** 所有状态回初值。换任务或离开页面时调用，回来时不会看到上一个任务的残留。 */
  function reset(): void {
    loadToken += 1
    closeStream()
    taskNo.value = ''
    snapshot.value = null
    loadError.value = null
    artifacts.value = []
    languages.value = []
    selectedSourceLanguage.value = ''
    selectedTargetLanguage.value = 'en'
    subtitles.reset()
    keywordStore.reset()
  }

  /** 关掉进度流并清掉运行态。store 不随路由销毁，离开页面必须显式调。 */
  function closeStream(): void {
    runEvents.close()
    activeRunNo.value = null
    runProgress.value = null
    triggering.value = false
    streamError.value = null
    translationInvalidated.value = false
  }

  /** 打开页面时恢复进度：快照里最近一次执行还在跑，就跟那条 run。 */
  function resumeRunIfNeeded(): void {
    const run = snapshot.value?.lastRun
    if (run === null || run === undefined) return
    if (run.status !== TASK_RUN_STATUS.RUNNING) return
    startRun({ taskNo: taskNo.value, runNo: run.runNo })
  }

  /** 开始跟一条 run 的进度；触发接口拿到引用、打开页面恢复时都走这里。 */
  function startRun(runRef: TaskRunRef): void {
    // 跨任务的事件不接：调用点都该先把页面切到对应任务
    if (runRef.taskNo !== taskNo.value) return
    activeRunNo.value = runRef.runNo
    runProgress.value = null
    streamError.value = null
    runEvents.start(runRef.taskNo, runRef.runNo)
  }

  /** 手动重试进度流：三次重试用尽后，顶栏的「重试」调它。 */
  function retryStream(): void {
    const runNo = activeRunNo.value
    if (runNo === null) return
    streamError.value = null
    runEvents.start(taskNo.value, runNo)
  }

  /**
   * 按关键词重新生成字幕：把当前完整清单交上去。
   * 永远发数组——空数组是「清空关键词再跑」，null 的语义是「清单不动」，本页用不到。
   */
  async function regenerate(): Promise<void> {
    if (isBusy.value) return
    await triggerRun(() =>
      regenerateSubtitles(taskNo.value, { keywords: [...keywordStore.keywords] }),
    )
  }

  /** 按选中的源 / 目标语种触发翻译。 */
  async function translate(): Promise<void> {
    if (isBusy.value) return
    if (!selectedSourceLanguage.value) return
    if (selectedSourceLanguage.value === selectedTargetLanguage.value) return
    await triggerRun(() =>
      translateTask(taskNo.value, {
        sourceLanguage: selectedSourceLanguage.value,
        targetLanguage: selectedTargetLanguage.value,
      }),
    )
  }

  /** 换源语种。 */
  function selectSourceLanguage(code: string): void {
    selectedSourceLanguage.value = code
  }

  /** 源 / 目标语种互换；换完的目标语种如果有现成译文，直接挂上。 */
  async function swapLanguages(): Promise<void> {
    const previousSource = selectedSourceLanguage.value
    selectedSourceLanguage.value = selectedTargetLanguage.value
    await selectTargetLanguage(previousSource)
  }

  /**
   * 换目标语种：已经翻过的语种直接挂上，没翻过的先清空、等用户点翻译。
   */
  async function selectTargetLanguage(code: string): Promise<void> {
    selectedTargetLanguage.value = code
    const artifact = pickArtifact(artifacts.value, ARTIFACT_TYPE.TRANSLATED, code)
    if (artifact === null) {
      subtitles.clearTranslation()
      return
    }
    await subtitles.loadTranslation(artifact, code)
  }

  /**
   * 触发类请求的共用流水线。
   *
   * - 请求在途时借 connecting 挡住连点；
   * - 202 与 409 都带 run 引用，409 不是纯错误，跟那条正在跑的 run；
   * - 其余错误落到 streamError，由字幕区顶部的错误条显示。
   */
  async function triggerRun(request: () => Promise<TaskRunRef>): Promise<void> {
    triggering.value = true
    streamError.value = null
    try {
      startRun(await request())
    } catch (error) {
      if (error instanceof ApiError && error.status === 409 && error.runRef !== null) {
        startRun(error.runRef)
        return
      }
      streamError.value = error instanceof ApiError ? error.message : '请求失败，请稍后重试'
    } finally {
      triggering.value = false
    }
  }

  /**
   * 加载一个任务的全套数据。
   *
   * 顺序：快照决定有没有东西可拉，产物清单决定文件地址，段落与上下文决定页面内容，
   * 关键词与语种清单互不依赖、并行。附属请求失败不该把整页拖进错误卡——
   * 唯一的例外是字幕本身拉不到。
   */
  async function loadAll(options: { reloadKeywords?: boolean } = {}): Promise<void> {
    const token = (loadToken += 1)
    const current = taskNo.value
    loadError.value = null

    const [languageResult, snapshotResult] = await Promise.allSettled([
      getLanguages(),
      getTask(current),
    ])
    // 切任务后迟到的响应不作数
    if (token !== loadToken) return

    if (languageResult.status === 'fulfilled') {
      languages.value = languageResult.value
    } else {
      languages.value = []
    }

    if (snapshotResult.status === 'rejected') {
      const error = snapshotResult.reason
      const err = error instanceof ApiError ? error : null
      loadError.value =
        err?.status === 404
          ? { kind: WORKSPACE_LOAD_ERROR_KIND.NOT_FOUND }
          : {
              kind: WORKSPACE_LOAD_ERROR_KIND.ERROR,
              message: err?.message ?? '加载失败，请稍后重试',
            }
      return
    }

    const loaded = snapshotResult.value

    if (loaded.lastRun === null) {
      snapshot.value = loaded
      loadError.value = {
        kind: WORKSPACE_LOAD_ERROR_KIND.ERROR,
        message: '这个任务还没有任何执行记录，请重新提交',
      }
      return
    }

    // 跑着也照样拉产物：翻译 / 重新生成期间，上一版字幕仍然要看得到
    const artifactList = await listArtifacts(current).catch(() => null)
    if (token !== loadToken) return
    if (artifactList === null) {
      snapshot.value = loaded
      loadError.value = {
        kind: WORKSPACE_LOAD_ERROR_KIND.ERROR,
        message: '读取产物清单失败，请稍后重试',
      }
      return
    }
    artifacts.value = artifactList

    const cleanedArtifact = pickArtifact(artifactList, ARTIFACT_TYPE.CLEANED)
    if (!cleanedArtifact) {
      snapshot.value = loaded
      // 跑成功了却没产物，是加载侧的异常，写成加载失败；
      // 失败 / 中断而没产物是执行侧的问题，不写 loadError——让 phase 走 run-failed，
      // 失败卡上要显示后端给的失败原因。
      if (loaded.lastRun.status === TASK_RUN_STATUS.SUCCEEDED) {
        loadError.value = {
          kind: WORKSPACE_LOAD_ERROR_KIND.ERROR,
          message: '任务已完成，但找不到可用的字幕产物',
        }
      }
      return
    }

    const subtitleError = await subtitles.load(cleanedArtifact)
    if (token !== loadToken) return
    if (subtitleError !== null) {
      snapshot.value = loaded
      loadError.value = { kind: WORKSPACE_LOAD_ERROR_KIND.ERROR, message: subtitleError }
      return
    }

    // 先定下拉的初值，再按目标语种找有没有现成译文
    applyLanguages()
    await reloadTranslation(loaded.lastRun)
    if (token !== loadToken) return

    // 关键词是附属信息：拉不到就当作没设，不把整页打成错误
    if (options.reloadKeywords !== false) {
      await keywordStore.load(current)
      if (token !== loadToken) return
    }

    // 快照最后落：phase 是纯 computed，先落快照会让页面在 ready 与 error 之间闪一下
    // （刚成功、字幕还没拉回来时，phase 会看到「已成功但没字幕」）
    snapshot.value = loaded
  }

  /**
   * 刷新后该挂哪一版译文：先看当前正显示的语种；没显示过，再看最近一次执行是不是翻译成功；
   * 还不是，就看当前目标语种有没有现成译文——有就直接显示，不必让用户再点一次翻译。
   */
  async function reloadTranslation(run: RunResponse | null): Promise<void> {
    const language = resolveTranslatedLanguage(run)
    const artifact =
      language === null ? null : pickArtifact(artifacts.value, ARTIFACT_TYPE.TRANSLATED, language)
    await subtitles.loadTranslation(artifact, language)
  }

  /** 挑出这次刷新要显示的译文语种；没有现成译文就返回 null。 */
  function resolveTranslatedLanguage(run: RunResponse | null): string | null {
    if (subtitles.translatedLanguage !== null) return subtitles.translatedLanguage

    if (run?.runType === TASK_RUN_TYPE.TRANSLATE && run.status === TASK_RUN_STATUS.SUCCEEDED) {
      return run.targetLanguage
    }

    const target = selectedTargetLanguage.value
    return pickArtifact(artifacts.value, ARTIFACT_TYPE.TRANSLATED, target) === null ? null : target
  }

  /** 关键词拉回后，按 context 与段落里的语种补齐源 / 目标语种的初值；用户已经选过的合法值不覆盖。 */
  function applyLanguages(): void {
    const options = languages.value
    const fromContext = normalizeLanguageCode(subtitles.context?.sourceLanguage ?? '', options)
    const fromTrack = normalizeLanguageCode(subtitles.cleanedTrack?.language ?? '', options)

    if (!options.some((option) => option.code === selectedSourceLanguage.value)) {
      selectedSourceLanguage.value = fromContext ?? fromTrack ?? ''
    }
    if (!options.some((option) => option.code === selectedTargetLanguage.value)) {
      selectedTargetLanguage.value = options.some((option) => option.code === 'en')
        ? 'en'
        : (options[0]?.code ?? 'en')
    }
  }

  /** 收一条进度：只收当前 run 的，且百分比不许回退。 */
  function applyProgress(progress: TaskRunProgress): void {
    if (progress.runNo !== activeRunNo.value) return
    if (runProgress.value !== null && hasRunProgressRegressed(runProgress.value, progress)) return
    runProgress.value = progress
  }

  /** 收到终态事件：交给 finishRun 收口。 */
  function handleTerminal(progress: TaskRunProgress): void {
    if (progress.runNo !== activeRunNo.value) return
    void finishRun({
      runType: progress.runType,
      status: progress.status,
      targetLanguage: progress.targetLanguage ?? null,
    })
  }

  /**
   * 断线后确诊：拉一次快照。
   *
   * 204（run 已终态）和 404（run 不存在）在浏览器眼里都只是「连接关了」，
   * 所以不区分两者——任务 404 就别再试；run 已不是 RUNNING 就按终态收口；
   * 仍然是 RUNNING 才退避重连。
   */
  async function shouldReconnect(): Promise<boolean> {
    try {
      const latest = await getTask(taskNo.value)
      const run = latest.lastRun
      if (run === null) {
        snapshot.value = latest
        activeRunNo.value = null
        return false
      }
      if (run.status === TASK_RUN_STATUS.RUNNING) return true
      await finishRun({
        runType: run.runType,
        status: run.status,
        targetLanguage: run.targetLanguage,
      })
      return false
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        activeRunNo.value = null
        loadError.value = { kind: WORKSPACE_LOAD_ERROR_KIND.NOT_FOUND }
        return false
      }
      // 断网 / 超时：继续退避重试
      return true
    }
  }

  /** 三次重试用尽：留个手动入口，不无限静默重试。 */
  function handleRetryExhausted(): void {
    streamError.value = '进度连接中断，请重试'
  }

  /**
   * 一条 run 结束后的收口，不管终态是从流里收到的还是断线后从快照补判的。
   * 进门先把 activeRunNo 清掉：两条收口路径同时进来时，第二条直接返回。
   */
  async function finishRun(finished: {
    runType: TaskRunType
    status: TaskRunStatus
    targetLanguage: string | null
  }): Promise<void> {
    if (activeRunNo.value === null) return
    activeRunNo.value = null
    runProgress.value = null

    const hadTranslation = artifacts.value.some(
      (artifact) => artifact.type === ARTIFACT_TYPE.TRANSLATED,
    )
    // 翻译成功先记下语种，reloadTranslation 要靠它挑译文
    if (
      finished.runType === TASK_RUN_TYPE.TRANSLATE &&
      finished.status === TASK_RUN_STATUS.SUCCEEDED
    ) {
      subtitles.setTranslatedLanguage(finished.targetLanguage)
    }

    // 刷新前先记下有没有内容可留：终态已经确认，只是内容没跟上时不该把整页换成错误卡
    const hadContent = subtitles.cleanedTrack !== null
    await refresh({ reloadKeywords: finished.runType !== TASK_RUN_TYPE.TRANSLATE })
    if (loadError.value !== null) {
      if (hadContent) {
        loadError.value = null
        streamError.value = '进度已结束，但刷新任务内容失败，请刷新页面'
      }
      return
    }

    if (
      finished.runType === TASK_RUN_TYPE.RECLEAN &&
      finished.status === TASK_RUN_STATUS.SUCCEEDED
    ) {
      // 重生成会作废下游译文；之前确实有译文才说「原译文已失效」
      translationInvalidated.value = hadTranslation
    }
    if (
      finished.runType === TASK_RUN_TYPE.TRANSLATE &&
      finished.status === TASK_RUN_STATUS.SUCCEEDED
    ) {
      translationInvalidated.value = false
    }
  }

  return {
    // state
    taskNo,
    snapshot,
    loadError,
    artifacts,
    languages,
    selectedSourceLanguage,
    selectedTargetLanguage,
    activeRunNo,
    runProgress,
    streamError,
    translationInvalidated,
    // computed
    lastRun,
    phase,
    streamState,
    activeRunType,
    runPercent,
    runMessage,
    isBusy,
    // actions
    open,
    refresh,
    reset,
    closeStream,
    startRun,
    retryStream,
    regenerate,
    translate,
    selectSourceLanguage,
    selectTargetLanguage,
    swapLanguages,
  }
})
