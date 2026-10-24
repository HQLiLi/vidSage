import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { listArtifacts } from '@/api/artifacts'
import { getLanguages } from '@/api/languages'
import { ApiError } from '@/api/request'
import { getTask } from '@/api/tasks'
import { ARTIFACT_TYPE } from '@/enums/subtitle'
import { TASK_RUN_STATUS } from '@/enums/task'
import { WORKSPACE_LOAD_ERROR_KIND, WORKSPACE_PHASE } from '@/enums/workspace'
import { useWorkspaceKeywordsStore } from '@/stores/workspaceKeywords'
import { useWorkspaceSubtitlesStore } from '@/stores/workspaceSubtitles'
import { pickArtifact } from '@/utils/artifacts'
import { normalizeLanguageCode } from '@/utils/language'
import type { ArtifactResponse, LanguageOption } from '@/types/subtitle'
import type { TaskResponse } from '@/types/task'

/**
 * 工作区加载失败态：任务不存在（404）与其他错误分列，页面据此渲染不同的引导卡。
 */
export type WorkspaceLoadError =
  | { kind: typeof WORKSPACE_LOAD_ERROR_KIND.NOT_FOUND }
  | { kind: typeof WORKSPACE_LOAD_ERROR_KIND.ERROR; message: string }

/** 页面当前处于哪种状态。 */
export type WorkspacePhase = (typeof WORKSPACE_PHASE)[keyof typeof WORKSPACE_PHASE]

/** 引导卡能显示的形态；就绪态走双栏，不进状态卡。 */
export type WorkspaceStatusKind = Exclude<WorkspacePhase, typeof WORKSPACE_PHASE.READY>

/**
 * 工作区的任务域：快照、产物清单、语种与页面状态，外加整套加载的编排。
 *
 * 字幕内容与关键词各在自己的 store 里（`workspaceSubtitles` / `workspaceKeywords`），
 * 这里只负责「先拉什么、失败了算谁的」，拉到手后交给对应的域。
 * 本 store 只管「读」——订阅进度流、触发重新生成与翻译是后一个任务的事。
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

  /** 加载的代际标记：切任务时靠它把上一次还没回来的请求作废。 */
  let loadToken = 0

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

  /**
   * 打开一个任务：清掉旧状态再加载。
   */
  async function open(nextTaskNo: string): Promise<void> {
    reset()
    taskNo.value = nextTaskNo
    await loadAll()
  }

  /**
   * 重新加载当前任务（错误卡的「重试」与运行中占位的「刷新」都走这里）。
   */
  async function refresh(): Promise<void> {
    await loadAll()
  }

  /** 所有状态回初值。页面卸载时调用，回来时不会看到上一个任务的残留。 */
  function reset(): void {
    loadToken += 1
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

  /**
   * 加载一个任务的全套数据。
   *
   * 顺序：快照决定有没有东西可拉，产物清单决定文件地址，段落与上下文决定页面内容，
   * 关键词与语种清单互不依赖、并行。附属请求失败不该把整页拖进错误卡——
   * 唯一的例外是字幕本身拉不到。
   */
  async function loadAll(): Promise<void> {
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
          : { kind: WORKSPACE_LOAD_ERROR_KIND.ERROR, message: err?.message ?? '加载失败，请稍后重试' }
      return
    }

    const loaded = snapshotResult.value
    snapshot.value = loaded

    if (loaded.lastRun === null) {
      loadError.value = { kind: WORKSPACE_LOAD_ERROR_KIND.ERROR, message: '这个任务还没有任何执行记录，请重新提交' }
      return
    }
    // 还在跑：本任务不订阅进度流，页面显示「执行中，请稍后刷新」
    if (loaded.lastRun.status === TASK_RUN_STATUS.RUNNING) return

    const artifactList = await listArtifacts(current).catch(() => null)
    if (token !== loadToken) return
    if (artifactList === null) {
      loadError.value = { kind: WORKSPACE_LOAD_ERROR_KIND.ERROR, message: '读取产物清单失败，请稍后重试' }
      return
    }
    artifacts.value = artifactList

    const cleanedArtifact = pickArtifact(artifactList, ARTIFACT_TYPE.CLEANED)
    if (!cleanedArtifact) {
      // 跑成功了却没产物，是加载侧的异常，写成加载失败；
      // 失败 / 中断而没产物是执行侧的问题，不写 loadError——让 phase 走 run-failed，
      // 失败卡上要显示后端给的失败原因。
      if (loaded.lastRun.status === TASK_RUN_STATUS.SUCCEEDED) {
        loadError.value = { kind: WORKSPACE_LOAD_ERROR_KIND.ERROR, message: '任务已完成，但找不到可用的字幕产物' }
      }
      return
    }

    const subtitleError = await subtitles.load(cleanedArtifact)
    if (token !== loadToken) return
    if (subtitleError !== null) {
      loadError.value = { kind: WORKSPACE_LOAD_ERROR_KIND.ERROR, message: subtitleError }
      return
    }

    // 关键词是附属信息：拉不到就当作没设，不把整页打成错误
    await keywordStore.load(current)
    if (token !== loadToken) return

    applyLanguages()
  }

  /** 关键词拉回后，按 context 与段落里的语种定下源语种与目标语种的初值。 */
  function applyLanguages(): void {
    const options = languages.value
    const fromContext = normalizeLanguageCode(subtitles.context?.sourceLanguage ?? '', options)
    const fromTrack = normalizeLanguageCode(subtitles.cleanedTrack?.language ?? '', options)
    selectedSourceLanguage.value = fromContext ?? fromTrack ?? ''

    if (options.some((option) => option.code === 'en')) {
      selectedTargetLanguage.value = 'en'
      return
    }
    selectedTargetLanguage.value = options[0]?.code ?? 'en'
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
    // computed
    lastRun,
    phase,
    // actions
    open,
    refresh,
    reset,
  }
})
