<script setup lang="ts">
// 工作区页（路由 /workspace/:taskNo）：打开一个任务，看它的字幕、关键词与主题。
// 布局照已验证的原型——左列竖排三块（媒体位 / 关键词 / 主题），右侧字幕区。
// 进度订阅、重新生成与翻译是下一步的事；这一页只管读。
import { computed, nextTick, onMounted, onUnmounted, useTemplateRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'

import AppHeader from '@/components/AppHeader.vue'
import { WORKSPACE_PHASE } from '@/enums/workspace'
import { gsap } from '@/lib/gsap'
import { useWorkspaceStore } from '@/stores/workspace'
import type { WorkspaceStatusKind } from '@/stores/workspace'
import KeywordPane from '@/views/workspace/components/KeywordPane.vue'
import MediaPane from '@/views/workspace/components/MediaPane.vue'
import StatusCard from '@/views/workspace/components/StatusCard.vue'
import ThemePane from '@/views/workspace/components/ThemePane.vue'
import TranscriptPane from '@/views/workspace/components/TranscriptPane.vue'
import WorkspaceHeader from '@/views/workspace/components/WorkspaceHeader.vue'

const props = defineProps<{
  /** 路由参数：对外任务号 taskNo，由路由配置的 props: true 注入。 */
  taskNo: string
}>()

const router = useRouter()

const store = useWorkspaceStore()
const { phase } = storeToRefs(store)
const { open, refresh, reset } = store

/** 引导卡要显示哪种形态（就绪态走双栏，不进这里）。 */
const statusKind = computed<WorkspaceStatusKind>(() =>
  phase.value === WORKSPACE_PHASE.READY ? WORKSPACE_PHASE.LOADING : phase.value,
)

/** 回上传页（各种引导卡的按钮）。 */
function goUpload(): void {
  void router.push('/')
}

// —— 入场动效 ——

const rootRef = useTemplateRef<HTMLElement>('root')
let entranceCtx: gsap.Context | undefined
let entrancePlayed = false

/** 就绪双栏入场（只播一次）：data-animate 目标 stagger 淡入上移；开「减少动态效果」的用户拿静态界面。 */
function playEntrance(): void {
  if (entrancePlayed) return
  const root = rootRef.value
  if (!root) return
  entrancePlayed = true
  entranceCtx = gsap.context(() => {
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
  void open(props.taskNo)
})

// 路由参数变了（切到别的任务）就重新加载
watch(
  () => props.taskNo,
  (next) => {
    void open(next)
  },
)

// 就绪双栏挂载后播入场
watch(phase, (value) => {
  if (value !== WORKSPACE_PHASE.READY) return
  void nextTick(playEntrance)
})

onUnmounted(() => {
  entranceCtx?.revert()
  reset()
})
</script>

<template>
  <div ref="root" class="flex h-screen min-h-0 flex-col bg-background">
    <!-- 就绪态：工作区顶栏 + 左三块右字幕的双栏 -->
    <template v-if="phase === WORKSPACE_PHASE.READY">
      <WorkspaceHeader data-animate="header" />
      <!-- overflow-clip：入场 y 位移让窗格瞬时下探出视口，h-screen 应用壳上会多出一条
           文档滚动条，动画结束再弹回；就绪态是固定视口布局，窗格溢出一律是动画残影 -->
      <main class="flex min-h-0 flex-1 flex-col overflow-clip lg:flex-row">
        <div
          class="overlay-scrollbar flex min-h-180 min-w-0 flex-col overflow-y-auto border-b border-border lg:min-h-0 lg:basis-[60%] lg:flex-none lg:overscroll-contain lg:border-b-0"
        >
          <MediaPane data-animate="media" />
          <KeywordPane data-animate="keywords" />
          <ThemePane data-animate="theme" />
        </div>
        <div
          class="flex min-h-130 min-w-0 shrink-0 flex-col border-t border-border lg:min-h-0 lg:w-[40%] lg:flex-none lg:border-l lg:border-t-0"
        >
          <TranscriptPane data-animate="transcript" class="h-full min-h-0 flex-1" />
        </div>
      </main>
    </template>

    <!-- 非就绪态：品牌顶栏 + 居中引导卡 -->
    <template v-else>
      <AppHeader />
      <main class="mx-auto flex w-full max-w-xl flex-1 flex-col justify-center px-4 py-10 sm:px-6">
        <StatusCard :kind="statusKind" @retry="refresh" @back="goUpload" />
      </main>
    </template>
  </div>
</template>
