<script setup lang="ts">
// 工作区顶栏（页面私有组件）：品牌 / 返回 / 任务标题 / 按需翻译入口 / 产物下载面板。
// 翻译态机：就绪可点 → isTranslating 禁用显示「翻译中…」→ 已有译文隐藏（R6）；
// 「产物」与翻译失败提示均为绝对定位小面板，document 点击外部收起（onScopeDispose 清监听）。
import { computed, onMounted, onScopeDispose, ref, useTemplateRef } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import axios from 'axios'

import BrandLogo from '@/components/BrandLogo.vue'
import { formatBytes } from '@/utils/format'
import { useTaskStore } from '@/stores/task'

const props = defineProps<{
  /** 对外任务号：产物下载链接的路径段。 */
  taskNo: string
}>()

const router = useRouter()

const store = useTaskStore()
// state / getter 用 storeToRefs，action 直接解构（state-management.md）
const { taskView, isTranslating, hasTranslation } = storeToRefs(store)
const { translate } = store

/** 「产物」面板展开态。 */
const artifactsOpen = ref(false)
const artifactsWrapRef = useTemplateRef<HTMLElement>('artifactsWrap')

/** 翻译失败的内联提示文案；null = 无待展示错误。 */
const translateError = ref<string | null>(null)
const translateWrapRef = useTemplateRef<HTMLElement>('translateWrap')

/** 产物清单：GET 快照的 artifacts；任务视图未就绪时为空表。 */
const artifacts = computed(() => taskView.value?.artifacts ?? [])

/** 产物下载地址：原生 <a download> 直连产物端点（同源相对路径），文件名经 URL 编码防御特殊字符。 */
function artifactHref(name: string): string {
  return `/api/tasks/${props.taskNo}/artifacts/${encodeURIComponent(name)}`
}

/** 回上传页。 */
function goUpload(): void {
  void router.push('/')
}

/** 展开 / 收起产物面板。 */
function toggleArtifacts(): void {
  artifactsOpen.value = !artifactsOpen.value
}

/** 后端错误响应体形状：`{ "error": 中文说明 }`（ApiExceptionHandler 的统一映射）。 */
interface ErrorBody {
  error: string
}

/** 判定未知响应体是否为后端错误体 `{ error: string }`。 */
function isErrorBody(data: unknown): data is ErrorBody {
  if (typeof data !== 'object' || data === null) return false
  return 'error' in data && typeof data.error === 'string'
}

/** 从未知错误提取面向用户的中文说明：axios 错误读响应体 error 字段，其余退回通用文案。 */
function extractTranslateError(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const data: unknown = error.response?.data
    if (isErrorBody(data)) return data.error
  }
  return '翻译请求失败，请稍后重试'
}

/** 触发按需翻译：进行中防重入；失败写入内联提示，面板内「重试」或再点「翻译」均可重发。 */
async function startTranslate(): Promise<void> {
  if (isTranslating.value) return
  translateError.value = null
  try {
    await translate()
  } catch (error) {
    translateError.value = extractTranslateError(error)
  }
}

/** 收起翻译失败提示（不改变翻译状态，翻译按钮始终可再次触发）。 */
function dismissTranslateError(): void {
  translateError.value = null
}

/** document 点击收口：点击落在面板锚点之外时，分别收起产物面板 / 清除翻译错误提示。 */
function onDocumentClick(event: MouseEvent): void {
  const target = event.target
  if (!(target instanceof Node)) return
  const artifactsWrap = artifactsWrapRef.value
  if (artifactsOpen.value && artifactsWrap && !artifactsWrap.contains(target)) {
    artifactsOpen.value = false
  }
  const translateWrap = translateWrapRef.value
  if (translateError.value && translateWrap && !translateWrap.contains(target)) {
    translateError.value = null
  }
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
})

// 卸载时摘除 document 监听，不留给全局（component-guidelines 副作用清理约定）
onScopeDispose(() => {
  document.removeEventListener('click', onDocumentClick)
})
</script>

<template>
  <header class="flex h-14 shrink-0 items-center gap-3 border-b border-border bg-surface px-4">
    <BrandLogo />
    <button
      type="button"
      class="flex shrink-0 items-center gap-1.5 rounded-lg px-2 py-1.5 text-sm text-muted transition-colors hover:bg-mist hover:text-foreground"
      @click="goUpload()"
    >
      <svg
        viewBox="0 0 24 24"
        class="h-4 w-4"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M19 12H5m6-7-7 7 7 7" />
      </svg>
      返回
    </button>
    <span class="h-4 w-px bg-border" aria-hidden="true"></span>
    <p class="min-w-0 flex-1 truncate text-sm font-medium">{{ taskView?.sourceUri ?? '' }}</p>

    <!-- 按需翻译入口：翻译中禁用、已有译文隐藏；失败提示面板锚定在按钮下方 -->
    <div v-if="!hasTranslation" ref="translateWrap" class="relative shrink-0">
      <button
        type="button"
        class="rounded-lg bg-primary px-4 py-1.5 text-sm font-medium text-background transition-colors hover:bg-primary-hover disabled:cursor-not-allowed disabled:opacity-50"
        :disabled="isTranslating"
        @click="startTranslate()"
      >
        {{ isTranslating ? '翻译中…' : '翻译' }}
      </button>
      <div
        v-if="translateError"
        class="absolute right-0 top-full z-20 mt-2 w-64 rounded-xl border border-border bg-background p-3 shadow-lg"
        role="alert"
      >
        <p class="text-sm leading-relaxed text-ink">{{ translateError }}</p>
        <div class="mt-2.5 flex justify-end gap-2">
          <button
            type="button"
            class="rounded-lg border border-border px-3 py-1.5 text-xs font-medium transition-colors hover:border-foreground"
            @click="dismissTranslateError()"
          >
            知道了
          </button>
          <button
            type="button"
            class="rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-background transition-colors hover:bg-primary-hover"
            @click="startTranslate()"
          >
            重试
          </button>
        </div>
      </div>
    </div>

    <!-- 产物面板：artifacts 清单（文件名 + 体积），原生 <a download> 直连下载端点（R6） -->
    <div ref="artifactsWrap" class="relative shrink-0">
      <button
        type="button"
        class="rounded-lg border border-border px-3 py-1.5 text-sm transition-colors hover:border-foreground"
        :aria-expanded="artifactsOpen"
        @click="toggleArtifacts()"
      >
        产物
      </button>
      <div
        v-if="artifactsOpen"
        class="absolute right-0 top-full z-20 mt-2 w-72 rounded-xl border border-border bg-background p-2 shadow-lg"
      >
        <p class="px-2 pb-1 pt-1.5 text-xs font-medium text-muted">产物清单</p>
        <a
          v-for="artifact in artifacts"
          :key="artifact.name"
          :href="artifactHref(artifact.name)"
          :download="artifact.name"
          class="flex items-center justify-between gap-3 rounded-lg px-2 py-2 text-sm transition-colors hover:bg-mist"
        >
          <span class="min-w-0 truncate">{{ artifact.name }}</span>
          <span class="shrink-0 text-xs tabular-nums text-muted">
            {{ formatBytes(artifact.sizeBytes) }}
          </span>
        </a>
        <p v-if="artifacts.length === 0" class="px-2 py-3 text-center text-xs text-faint">
          暂无可下载的产物
        </p>
      </div>
    </div>
  </header>
</template>
