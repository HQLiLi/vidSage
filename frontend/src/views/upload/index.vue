<script setup lang="ts">
// 上传页（路由 `/`）：本地文件 / 链接双模式提交转写任务。提交成功即跳工作区，
// 阶段进度不在本页消费（design §3.5：上传页只管提交与上传百分比进度）。
import { computed, onMounted, onUnmounted, ref, useTemplateRef } from 'vue'
import { useRouter } from 'vue-router'

import AppHeader from '@/components/AppHeader.vue'
import HeroWave from '@/views/upload/components/HeroWave.vue'
import { submitUpload, submitUrl } from '@/api/tasks'
import { ApiError } from '@/api/request'
import { gsap } from '@/lib/gsap'
import type { TaskView } from '@/types/task'

/** 上传方式：本地文件 / 链接 */
type InputMode = 'local' | 'link'

/**
 * 提交失败的页内展示模型：409 冲突携带既有任务号（渲染「查看该任务」跳转），
 * 其余失败只带面向用户的文案。
 */
type SubmitError =
  { kind: 'conflict'; message: string; existingTaskNo: string } | { kind: 'message'; text: string }

/** 上传方式候选（segmented control 的渲染顺序） */
const INPUT_MODES: InputMode[] = ['local', 'link']

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
 * 把提交失败的未知错误归一为页内展示模型：拦截器已归一为 ApiError——409 冲突
 * 读 existingTask（渲染「查看该任务」跳既有任务工作区），其余读统一文案；
 * 非 ApiError（不经 request 的本地异常）退回通用文案。
 */
function toSubmitError(error: unknown): SubmitError {
  const err = error instanceof ApiError ? error : null
  if (err?.existingTask) {
    return { kind: 'conflict', message: err.message, existingTaskNo: err.existingTask.taskNo }
  }
  return { kind: 'message', text: err?.message ?? '提交失败，请稍后重试' }
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
    <AppHeader />

    <main class="mx-auto flex w-full max-w-2xl flex-1 flex-col justify-center px-4 py-10 sm:px-6">
      <!-- 氛围横幅：全页唯一的色彩时刻（深绿声波 = "Sage"） -->
      <div data-animate="hero" class="relative overflow-hidden rounded-2xl">
        <HeroWave class="block h-44 w-full sm:h-48" />
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
