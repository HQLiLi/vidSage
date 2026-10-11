<script setup lang="ts">
// 工作区页（路由 /workspace/:taskNo）：GET 快照 + SSE 驱动的五态状态机壳
// （loading / 进度卡 / 失败卡 / 任务不存在卡 / 就绪双栏，design §3.4）。
// 就绪态经 useTranscriptPlayer 把媒体面板与字幕稿联动；入场动效见 design §4 #2。
import { computed, nextTick, onMounted, onUnmounted, ref, useTemplateRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'

import AppHeader from '@/components/AppHeader.vue'
import dayjs from '@/lib/day'
import { gsap } from '@/lib/gsap'
import { useTaskStore } from '@/stores/task'
import { useTranscriptPlayer } from '@/composables/useTranscriptPlayer'
import MediaPane from '@/views/workspace/components/MediaPane/index.vue'
import TranscriptPane from '@/views/workspace/components/TranscriptPane.vue'
import WorkspaceHeader from '@/views/workspace/components/WorkspaceHeader.vue'

const props = defineProps<{
  /** 路由参数：对外任务号 taskNo，由路由配置的 props: true 注入；查询 / 订阅 / 产物都凭它。 */
  taskNo: string
}>()

const router = useRouter()

const store = useTaskStore()
const { taskView, progress, cues, loadError, isRunning, isFailed, isSucceeded, reconnecting } =
  storeToRefs(store)
const { load, subscribe } = store

/** 字幕联动播放器：媒体元素唯一时间源，媒体面板与字幕稿共享同一实例 */
const player = useTranscriptPlayer(cues)

// —— 进度卡（isRunning）——

/** 「已运行 x 分钟」的刷新间隔：墙钟推进不依赖下一条 SSE 事件。 */
const CLOCK_TICK_MS = 30_000

/** 当前时刻（每 CLOCK_TICK_MS 刷新一次），驱动已运行时长的重算。 */
const nowTtime = ref(Date.now())
let clockTimer = 0

/** 进度卡阶段文案：SSE 阶段事件优先；事件未到（刚提交 / 快照无阶段）给排队兜底文案。 */
const stageMessage = computed(() => progress.value?.stage?.message ?? '任务排队中，等待开始处理…')

/** 进度卡百分比：阶段事件内的 0–100 进度；无事件时为 0。 */
const stagePercent = computed(() => progress.value?.stage?.progress ?? 0)

/** 进度卡「已提交 hh:mm」：提交时刻的当日时分（dayjs，design §2.1 的日历时间口径）。 */
const submittedAtText = computed(() => {
  const view = taskView.value
  return view ? dayjs(view.createdAt).format('HH:mm') : '--'
})

/** 进度卡「已运行 x 分钟」：从提交时刻起算的整分钟数（含排队等待，不足 1 分钟为 0）。 */
const elapsedMinutes = computed(() => {
  const view = taskView.value
  if (!view) return 0
  return Math.max(0, Math.floor((nowTtime.value - dayjs(view.createdAt).valueOf()) / 60_000))
})

// —— 失败卡（isFailed）——

/** 失败卡的时刻格式：带月日，跨天任务也不歧义。 */
const CALENDAR_FORMAT = 'MM-DD HH:mm'

/** 失败卡错误文案：GET 快照的 errorMessage（中文）；终态快照刷新未完成时给兜底文案。 */
const failedMessage = computed(
  () => taskView.value?.errorMessage ?? '处理过程中出现错误，未返回具体原因',
)

/** 失败卡「提交于」时刻；快照缺失时显示占位。 */
const submittedAtFull = computed(() => {
  const view = taskView.value
  return view ? dayjs(view.createdAt).format(CALENDAR_FORMAT) : '--'
})

/** 失败卡「失败于」时刻；finishedAt 缺失（快照未刷新）时显示占位。 */
const finishedAtText = computed(() => {
  const finishedAt = taskView.value?.finishedAt
  return finishedAt ? dayjs(finishedAt).format(CALENDAR_FORMAT) : '--'
})

// —— 装配与导航 ——

/** 回上传页（失败卡 / 任务不存在卡 / 加载失败卡的引导按钮）。 */
function goUpload(): void {
  void router.push('/')
}

/**
 * 串行编排：先 load 快照再 subscribe SSE——subscribe 依赖快照里的 taskNo
 * （store 约定：taskView 为空时空转），404 / 加载失败时不会建立订阅。
 */
async function loadThenSubscribe(): Promise<void> {
  await load(props.taskNo)
  subscribe()
}

// —— 入场动效（design §4 #2，硬规则同上传页）——

const rootRef = useTemplateRef<HTMLElement>('root')
let entranceCtx: gsap.Context | undefined
let entrancePlayed = false

/** 就绪双栏入场（幂等单次）：data-animate 目标 stagger 淡入上移；reduced-motion 走静态降级。 */
function playEntrance(): void {
  if (entrancePlayed) return
  const root = rootRef.value
  if (!root) return
  entrancePlayed = true
  entranceCtx = gsap.context(() => {
    // 动效只建在 no-preference 分支：开启「减少动态效果」的用户直接拿静态界面
    const mm = gsap.matchMedia()
    mm.add('(prefers-reduced-motion: no-preference)', () => {
      gsap.from('[data-animate]', {
        autoAlpha: 0,
        duration: 0.4,
        ease: 'power2.out',
        stagger: 0.08,
        y: 16,
      })
    })
  }, root)
}

onMounted(() => {
  clockTimer = window.setInterval(() => {
    nowTtime.value = Date.now()
  }, CLOCK_TICK_MS)
  void loadThenSubscribe()
})

// 就绪双栏挂载后播放入场：覆盖「刷新即成功」与「进度卡 → 成功」两条路径
watch(isSucceeded, (ready) => {
  if (!ready) return
  void nextTick(playEntrance)
})

onUnmounted(() => {
  window.clearInterval(clockTimer)
  entranceCtx?.revert()
})
</script>

<template>
  <div ref="root" class="flex h-screen min-h-0 flex-col bg-background">
    <!-- 重连提示条：SSE 断线重连中。shrink-0 让它在 flex 列里推开下方内容而非遮挡
         （AC5 要求不遮挡进度）；就绪态与非就绪态共用这一条，故放根级、置于两分支之前 -->
    <p
      v-if="reconnecting"
      role="status"
      class="shrink-0 border-b border-border bg-background py-2 text-center text-sm text-muted"
    >
      连接中断，正在重连…
    </p>

    <!-- 就绪态：工作区顶栏 + 左媒体右字幕双栏（布局对齐原型，design §3.4） -->
    <template v-if="isSucceeded && taskView">
      <WorkspaceHeader :task-no="taskNo" data-animate="header" />
      <!-- overflow-clip：入场 y 位移让窗格瞬时下探出视口，h-screen 应用壳上会多出一条
           文档滚动条（Windows 经典滚动条占宽挤压布局），动画结束再弹回；就绪态为固定
           视口布局，窗格溢出一律是动画残影，剪在 main，header 下拉面板在壳层不受影响 -->
      <main class="flex min-h-0 flex-1 flex-col overflow-clip lg:flex-row">
        <MediaPane
          data-animate="media"
          class="min-h-0 lg:basis-[58%] lg:flex-1"
          :task-no="taskNo"
          :input-type="taskView.inputType"
          :source-uri="taskView.sourceUri"
          :player="player"
        />
        <TranscriptPane
          data-animate="transcript"
          class="min-h-0 shrink-0 border-t border-border lg:w-[420px] lg:border-l lg:border-t-0 xl:w-[460px]"
          :cues="cues"
          :player="player"
        />
      </main>
    </template>

    <!-- 非就绪态：品牌顶栏 + 居中卡片（进度 / 失败 / 任务不存在 / 加载中） -->
    <template v-else>
      <AppHeader />
      <main class="mx-auto flex w-full max-w-xl flex-1 flex-col justify-center px-4 py-10 sm:px-6">
        <!-- 进度卡：SSE 阶段事件驱动，dayjs 显示提交时刻与已运行时长（AC7） -->
        <div v-if="isRunning" class="rounded-2xl border border-border bg-background p-5">
          <div class="flex items-center justify-between gap-3 text-sm">
            <span class="min-w-0 truncate font-medium">{{ stageMessage }}</span>
            <span class="shrink-0 tabular-nums text-muted">{{ stagePercent }}%</span>
          </div>
          <progress
            class="stage-progress mt-3 h-1.5 w-full appearance-none overflow-hidden rounded-full bg-mist"
            :value="stagePercent"
            max="100"
            aria-label="解析进度"
          ></progress>
          <p class="mt-3 text-xs text-muted">
            已提交 {{ submittedAtText }} · 已运行 {{ elapsedMinutes }} 分钟
          </p>
        </div>

        <!-- 失败卡：后端 errorMessage + 提交/失败时刻 + 重新上传（AC6 / AC7） -->
        <div
          v-else-if="isFailed"
          class="rounded-2xl border border-border bg-background p-5"
          role="alert"
        >
          <h2 class="text-sm font-semibold">任务处理失败</h2>
          <p class="mt-2 text-sm leading-relaxed text-ink">{{ failedMessage }}</p>
          <p class="mt-3 text-xs text-muted">
            提交于 {{ submittedAtFull }} · 失败于 {{ finishedAtText }}
          </p>
          <button
            type="button"
            class="mt-4 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-primary-hover"
            @click="goUpload()"
          >
            重新上传
          </button>
        </div>

        <!-- 任务不存在 / 加载失败卡：页内引导回上传页，不动用全局 404 路由 -->
        <div
          v-else-if="loadError"
          class="rounded-2xl border border-border bg-background p-5"
          role="alert"
        >
          <template v-if="loadError.kind === 'not-found'">
            <h2 class="text-sm font-semibold">任务不存在</h2>
            <p class="mt-2 text-sm leading-relaxed text-ink">
              没有找到任务 {{ taskNo }}，链接可能输入有误，或任务已被清理。
            </p>
          </template>
          <template v-else>
            <h2 class="text-sm font-semibold">加载失败</h2>
            <p class="mt-2 text-sm leading-relaxed text-ink">{{ loadError.message }}</p>
          </template>
          <button
            type="button"
            class="mt-4 rounded-lg border border-border px-4 py-2 text-sm font-medium transition-colors hover:border-foreground"
            @click="goUpload()"
          >
            返回上传
          </button>
        </div>

        <!-- loading：GET 快照请求中 -->
        <p v-else class="animate-pulse text-center text-sm text-muted motion-reduce:animate-none">
          正在加载任务…
        </p>
      </main>
    </template>
  </div>
</template>

<style scoped lang="scss">
/*
 * 解析进度条填充（轨道底色与圆角由元素自身的 bg-mist / rounded-full 承担）：
 * 原生 progress 元素 + :value 绑定真实百分比，归零 appearance 后分别给
 * WebKit 与 Firefox 的填充伪元素上 primary 色，与上传页的进度条同一视觉。
 */
.stage-progress {
  &::-webkit-progress-bar {
    background-color: var(--color-mist);
  }

  &::-webkit-progress-value {
    background-color: var(--color-primary);
    transition: width 0.15s;
  }

  &::-moz-progress-bar {
    background-color: var(--color-primary);
    transition: width 0.15s;
  }
}
</style>
