<script setup lang="ts">
// 上传页（路由 `/`）：本地文件 / 链接双模式提交转写任务。提交成功即跳工作区，
// 阶段进度不在本页消费（design §3.5：上传页只管提交与上传百分比进度）。
import { computed, onMounted, onUnmounted, ref, useTemplateRef } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'

import BrandLogo from '@/components/BrandLogo.vue'
import { submitUpload, submitUrl } from '@/api/tasks'
import { gsap } from '@/lib/gsap'
import type { TaskConflictResponse, TaskView } from '@/types/task'

/** 上传方式：本地文件 / 链接 */
type InputMode = 'local' | 'link'

/**
 * 提交失败的页内展示模型：409 冲突携带既有任务号（渲染「查看该任务」跳转），
 * 其余失败只带面向用户的文案。
 */
type SubmitError =
  { kind: 'conflict'; message: string; existingTaskNo: string } | { kind: 'message'; text: string }

/** Hero 声波横幅的单根波形：装饰性列表，位置序号即身份（作 v-for key）。 */
interface WaveBar {
  id: number
  /** 水平位置（viewBox px） */
  x: number
  /** 波形高度（viewBox px） */
  height: number
  /** 主层竖直起点（viewBox px） */
  y: number
  /** 影层竖直起点：主层整体下移 4px，形成双层描边视觉 */
  shadowY: number
  /** 主层不透明度：越高越亮，模拟声音能量 */
  opacity: number
}

/** 上传方式候选（segmented control 的渲染顺序） */
const INPUT_MODES: InputMode[] = ['local', 'link']

// Hero 波形几何常量：viewBox 720x200，90 根 * 8px 间距恰好铺满，基线居中于 y=100
const WAVE_BAR_COUNT = 90
const WAVE_BAR_GAP = 8
const WAVE_BASELINE = 100
const WAVE_MIN_HEIGHT = 8
const WAVE_AMPLITUDE = 66

const router = useRouter()

/** 当前上传方式 */
const inputMode = ref<InputMode>('local')
/** 链接输入框内容 */
const url = ref('')
/** 拖放悬停高亮 */
const dragging = ref(false)
/** 提交进行中（本地 / 链接共用）：防重入与按钮禁用的唯一依据 */
const submitting = ref(false)
/** 本地文件上传百分比（0–100，submitUpload 进度回调回写） */
const uploadPercent = ref(0)
/** 最近一次提交失败的展示模型；null = 无待展示错误 */
const submitError = ref<SubmitError | null>(null)

const rootRef = useTemplateRef<HTMLElement>('root')
const fileInputRef = useTemplateRef<HTMLInputElement>('fileInput')

/** 是否渲染上传进度卡：仅本地文件提交中切换（链接提交保持表单、按钮禁用）。 */
const showUploadProgress = computed(() => submitting.value && inputMode.value === 'local')

/** 链接是否可提交：trim 后非空（空链接用按钮禁用挡下，而不是等后端 400）。 */
const urlCanSubmit = computed(() => url.value.trim() !== '')

/**
 * Hero 声波横幅的 90 根波形：三个不同频率 / 相位的正弦叠加取绝对值，
 * 输出确定性伪随机高度（8–74px）——与音频卡片波形同一视觉语言，且每次渲染完全一致。
 */
const waveBars = computed<WaveBar[]>(() => {
  const bars: WaveBar[] = []
  for (let i = 0; i < WAVE_BAR_COUNT; i++) {
    const t =
      Math.sin(i * 0.41) * 0.5 + Math.sin(i * 0.13 + 2.1) * 0.35 + Math.sin(i * 0.67 + 0.7) * 0.15
    const height = WAVE_MIN_HEIGHT + Math.abs(t) * WAVE_AMPLITUDE
    bars.push({
      id: i,
      x: i * WAVE_BAR_GAP,
      height,
      y: WAVE_BASELINE - height / 2,
      shadowY: WAVE_BASELINE + 4 - height / 2,
      opacity: 0.22 + (height / (WAVE_MIN_HEIGHT + WAVE_AMPLITUDE)) * 0.55,
    })
  }
  return bars
})

/** 后端错误响应体形状：`{ "error": 中文说明 }`（ApiExceptionHandler 的统一映射）。 */
interface ErrorBody {
  error: string
}

/** 判定未知响应体是否为后端错误体 `{ error: string }`。 */
function isErrorBody(data: unknown): data is ErrorBody {
  if (typeof data !== 'object' || data === null) return false
  return 'error' in data && typeof data.error === 'string'
}

/** 判定未知响应体是否为 409 冲突体 TaskConflictResponse（error + 含 taskNo 的既有任务视图）。 */
function isConflictBody(data: unknown): data is TaskConflictResponse {
  if (typeof data !== 'object' || data === null) return false
  if (!('error' in data) || typeof data.error !== 'string') return false
  if (
    !('existingTask' in data) ||
    typeof data.existingTask !== 'object' ||
    data.existingTask === null
  ) {
    return false
  }
  return 'taskNo' in data.existingTask
}

/**
 * 把提交失败的未知错误归一为页内展示模型：409 读 TaskConflictResponse（后端说明 +
 * 既有任务号），其余 axios 错误读响应体 `{ "error": string }`，都拿不到时退回通用文案。
 * 全程类型守卫收窄，不用 as 断言（与 task store 的错误提取同口径）。
 */
function toSubmitError(error: unknown): SubmitError {
  if (axios.isAxiosError(error)) {
    const data: unknown = error.response?.data
    if (error.response?.status === 409 && isConflictBody(data)) {
      return { kind: 'conflict', message: data.error, existingTaskNo: data.existingTask.taskNo }
    }
    if (isErrorBody(data)) return { kind: 'message', text: data.error }
  }
  return { kind: 'message', text: '提交失败，请稍后重试' }
}

/**
 * 提交请求的公共收口：成功 202 后跳既有 taskNo 的工作区，失败记入页内错误模型
 * 内联展示。防重入由调用方先挡（进入前已置 submitting）。
 */
async function runSubmit(submit: Promise<TaskView>): Promise<void> {
  try {
    const view = await submit
    await router.push({ name: 'workspace', params: { taskNo: view.taskNo } })
  } catch (error) {
    submitError.value = toSubmitError(error)
  } finally {
    submitting.value = false
  }
}

/**
 * 提交本地文件：透传真实上传百分比驱动进度条，成功 202 后跳工作区。
 * 提交进行中再次触发直接忽略（防重入）。
 */
async function submitLocalFile(file: File): Promise<void> {
  if (submitting.value) return
  submitting.value = true
  uploadPercent.value = 0
  submitError.value = null
  await runSubmit(
    submitUpload(file, (percent) => {
      uploadPercent.value = percent
    }),
  )
}

/**
 * 提交链接任务：成功 202 后跳工作区。提交进行中再次触发直接忽略（防重入）；
 * 空链接不入请求（按钮禁用已挡，这里兜底）。
 */
async function submitLink(): Promise<void> {
  if (submitting.value) return
  const link = url.value.trim()
  if (!link) return
  submitting.value = true
  submitError.value = null
  await runSubmit(submitUrl({ url: link }))
}

/**
 * 接收拖放 / 文件选择产生的文件并提交：只收视频 / 音频（accept 属性只约束选择器，
 * 拖放绕得过，这里统一校验），类型不符给内联提示。
 */
function acceptFile(file: File | null | undefined): void {
  if (!file || submitting.value) return
  if (!file.type.startsWith('video/') && !file.type.startsWith('audio/')) {
    submitError.value = { kind: 'message', text: '仅支持视频或音频文件' }
    return
  }
  void submitLocalFile(file)
}

/** 打开系统文件选择器（触发隐藏 fileInput 的 click）。 */
function pickFile(): void {
  fileInputRef.value?.click()
}

/** 拖放落下：清除高亮并取第一个文件提交。 */
function onDrop(event: DragEvent): void {
  dragging.value = false
  acceptFile(event.dataTransfer?.files?.[0])
}

/** 隐藏 fileInput 的 change：取所选文件提交后清空 value，便于再次选择同一文件。 */
function onFileChange(event: Event): void {
  const target = event.target
  if (!(target instanceof HTMLInputElement)) return
  acceptFile(target.files?.[0])
  target.value = ''
}

/** 切换上传方式；提交进行中禁用切换（模板 disabled + 此处兜底），并清掉上一次的错误。 */
function switchMode(mode: InputMode): void {
  if (submitting.value) return
  inputMode.value = mode
  submitError.value = null
}

/** 跳转到 409 冲突既有任务的工作区。 */
function goToConflictTask(): void {
  const error = submitError.value
  if (!error || error.kind !== 'conflict') return
  void router.push({ name: 'workspace', params: { taskNo: error.existingTaskNo } })
}

/** 入场动效 context：onUnmounted 时 revert，防 tween 泄漏与幽灵动画（animation.md 硬规则）。 */
let ctx: gsap.Context | undefined

onMounted(() => {
  const root = rootRef.value
  if (!root) return
  ctx = gsap.context(() => {
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
})

onUnmounted(() => ctx?.revert())
</script>

<template>
  <div ref="root" class="flex min-h-screen flex-col bg-background">
    <header class="flex h-14 shrink-0 items-center border-b border-border px-4 sm:px-6">
      <BrandLogo />
    </header>

    <main class="mx-auto flex w-full max-w-2xl flex-1 flex-col justify-center px-4 py-10 sm:px-6">
      <!-- 氛围横幅：全页唯一的色彩时刻（深绿声波 = "Sage"） -->
      <div data-animate="hero" class="relative overflow-hidden rounded-2xl">
        <svg
          class="block h-44 w-full sm:h-48"
          viewBox="0 0 720 200"
          preserveAspectRatio="xMidYMid slice"
          aria-hidden="true"
        >
          <defs>
            <linearGradient id="upload-wave-sky" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" class="wave-sky-top" />
              <stop offset="1" class="wave-sky-bottom" />
            </linearGradient>
            <radialGradient id="upload-wave-glow" cx="0.82" cy="0.3" r="0.9">
              <stop offset="0" class="wave-glow-strong" />
              <stop offset="1" class="wave-glow-faint" />
            </radialGradient>
            <linearGradient id="upload-wave-shade" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0.45" class="wave-shade-clear" />
              <stop offset="1" class="wave-shade-deep" />
            </linearGradient>
            <linearGradient id="upload-wave-fade" x1="0" y1="0" x2="1" y2="0">
              <stop offset="0" class="wave-fade-deep" />
              <stop offset="1" class="wave-fade-clear" />
            </linearGradient>
            <filter id="upload-wave-grain">
              <feTurbulence
                type="fractalNoise"
                baseFrequency="0.9"
                numOctaves="2"
                stitchTiles="stitch"
              />
              <feColorMatrix type="matrix" values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 0.7 0" />
            </filter>
          </defs>
          <rect width="720" height="200" fill="url(#upload-wave-sky)" />
          <rect width="720" height="200" fill="url(#upload-wave-glow)" />
          <g v-for="bar in waveBars" :key="bar.id">
            <rect
              :x="bar.x"
              :y="bar.shadowY"
              width="3"
              :height="bar.height"
              rx="1.5"
              class="fill-sage-300"
              opacity="0.16"
            />
            <rect
              :x="bar.x"
              :y="bar.y"
              width="3"
              :height="bar.height"
              rx="1.5"
              class="fill-sage-50"
              :opacity="bar.opacity"
            />
          </g>
          <rect width="340" height="200" fill="url(#upload-wave-fade)" />
          <rect width="720" height="200" fill="url(#upload-wave-shade)" />
          <rect width="720" height="200" filter="url(#upload-wave-grain)" opacity="0.05" />
        </svg>
        <div class="absolute inset-x-0 bottom-0 p-5 sm:p-6">
          <h1 class="text-lg font-semibold text-background sm:text-xl">
            把音视频，变成可检索的文字
          </h1>
          <p class="mt-1 text-sm text-background/70">
            上传文件或粘贴链接，自动生成带时间码的字幕稿
          </p>
        </div>
      </div>

      <div data-animate="card" class="mt-5 rounded-2xl border border-border bg-background p-5">
        <!-- 上传进度态（仅本地文件）：submitUpload 的真实百分比 -->
        <template v-if="showUploadProgress">
          <div class="flex items-center justify-between text-sm">
            <span class="font-medium">上传中</span>
            <span class="tabular-nums text-muted">{{ uploadPercent }}%</span>
          </div>
          <progress
            class="upload-progress mt-3 h-1.5 w-full appearance-none overflow-hidden rounded-full bg-mist"
            :value="uploadPercent"
            max="100"
            aria-label="上传进度"
          ></progress>
          <p class="mt-3 text-xs text-muted">文件越大耗时越久，上传完成后自动进入工作区</p>
        </template>

        <template v-else>
          <div class="flex items-center justify-between gap-3">
            <span class="text-sm font-medium">上传方式</span>
            <div class="inline-flex rounded-lg border border-border p-0.5">
              <button
                v-for="mode in INPUT_MODES"
                :key="mode"
                type="button"
                class="rounded-md px-3 py-1.5 text-sm transition-colors disabled:cursor-not-allowed"
                :class="
                  inputMode === mode
                    ? 'bg-primary text-background'
                    : 'text-muted hover:text-foreground'
                "
                :disabled="submitting"
                @click="switchMode(mode)"
              >
                {{ mode === 'local' ? '本地文件' : '链接' }}
              </button>
            </div>
          </div>

          <div class="mt-4 grid">
            <!-- 本地文件面板：与链接面板叠同一网格单元，容器高度取最大值，切换不跳动 -->
            <div
              class="col-start-1 row-start-1"
              :class="inputMode === 'local' ? 'visible' : 'invisible pointer-events-none'"
            >
              <div
                class="rounded-xl border-2 border-dashed px-6 py-8 text-center transition-colors"
                :class="dragging ? 'border-foreground bg-mist' : 'border-border'"
                @dragover.prevent="dragging = true"
                @dragleave="dragging = false"
                @drop.prevent="onDrop($event)"
              >
                <p class="text-sm text-ink">将视频或音频文件拖到这里，或</p>
                <button
                  type="button"
                  class="mt-3 rounded-lg border border-border px-4 py-2 text-sm font-medium transition-colors hover:border-foreground"
                  @click="pickFile()"
                >
                  选择文件
                </button>
                <p class="mt-3 text-xs text-faint">支持常见视频 / 音频格式，单个文件上限 2GB</p>
              </div>
              <input
                ref="fileInput"
                type="file"
                accept="video/*,audio/*"
                class="hidden"
                @change="onFileChange($event)"
              />
            </div>

            <!-- 链接面板 -->
            <div
              class="col-start-1 row-start-1 self-start"
              :class="inputMode === 'link' ? 'visible' : 'invisible pointer-events-none'"
            >
              <form class="flex gap-2.5" @submit.prevent="submitLink()">
                <input
                  v-model="url"
                  type="text"
                  placeholder="https://…"
                  class="min-w-0 flex-1 rounded-lg border border-border bg-surface px-3.5 py-2.5 text-sm outline-none transition-colors placeholder:text-faint focus:border-foreground/40 focus:bg-background"
                />
                <button
                  type="submit"
                  class="shrink-0 rounded-lg bg-primary px-5 py-2.5 text-sm font-medium text-background transition-colors hover:bg-primary-hover disabled:cursor-not-allowed disabled:opacity-50"
                  :disabled="submitting || !urlCanSubmit"
                >
                  {{ submitting ? '提交中…' : '开始解析' }}
                </button>
              </form>
              <p class="mt-3 text-xs text-faint">支持 B 站 / YouTube 等视频页面链接</p>
            </div>
          </div>

          <!-- 提交错误（409 冲突 / 其余失败）：内联展示，不弹窗 -->
          <div
            v-if="submitError"
            class="mt-4 rounded-lg border border-border bg-surface px-4 py-3 text-sm"
            role="alert"
          >
            <template v-if="submitError.kind === 'conflict'">
              <p class="text-foreground">{{ submitError.message }}</p>
              <button
                type="button"
                class="mt-2 rounded-lg border border-border px-3 py-1.5 text-xs font-medium transition-colors hover:border-foreground"
                @click="goToConflictTask()"
              >
                查看该任务
              </button>
            </template>
            <p v-else class="text-foreground">{{ submitError.text }}</p>
          </div>
        </template>
      </div>
    </main>
  </div>
</template>

<style scoped lang="scss">
/*
 * Hero 渐变 stop 与原生进度条填充：引用 @/assets/tailwind.css 的 @theme 设计令牌。
 * 渐变 stop 色值没有对应的 Tailwind 工具类，走 scoped SCSS + CSS 变量；明暗渐变里的
 * 黑阶统一用 foreground 令牌而非裸 #000，维持「组件只引用 token」的约束。
 */
.wave-sky-top {
  stop-color: var(--color-sage-900);
}

.wave-sky-bottom {
  stop-color: var(--color-sage-700);
}

.wave-glow-strong {
  stop-color: var(--color-sage-300);
  stop-opacity: 0.1;
}

.wave-glow-faint {
  stop-color: var(--color-sage-300);
  stop-opacity: 0;
}

.wave-shade-clear {
  stop-color: var(--color-foreground);
  stop-opacity: 0;
}

.wave-shade-deep {
  stop-color: var(--color-foreground);
  stop-opacity: 0.45;
}

.wave-fade-deep {
  stop-color: var(--color-foreground);
  stop-opacity: 0.32;
}

.wave-fade-clear {
  stop-color: var(--color-foreground);
  stop-opacity: 0;
}

/*
 * 上传进度条填充（轨道底色与圆角由元素自身的 bg-mist / rounded-full 承担）：
 * 原生 progress 元素 + :value 绑定真实百分比，归零 appearance 后分别给
 * WebKit 与 Firefox 的填充伪元素上 primary 色，复用原型的进度条视觉。
 */
.upload-progress {
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
