<script setup lang="ts">
// 顶栏的「产物」按钮 + 右侧抽屉：按产物类型分组列出可下载的文件。
// 抽屉 Teleport 到 body：顶栏是 relative z-20 的层叠上下文，留在里面盖不过 main；
// 顶栏还挂着入场 GSAP 的 transform 残留，会让 fixed 退化成相对顶栏定位。
// 下载地址用清单里给的 url（后端拼好的全路径），前端不自己拼路径。
import { computed, nextTick, onScopeDispose, ref, useTemplateRef } from 'vue'
import { storeToRefs } from 'pinia'

import { ARTIFACT_TYPE } from '@/enums/subtitle'
import { useWorkspaceStore } from '@/stores/workspace'
import { ARTIFACT_TYPE_LABEL } from '@/utils/artifacts'
import { formatBytes } from '@/utils/format'
import type { ArtifactResponse } from '@/types/subtitle'

const store = useWorkspaceStore()
const { artifacts, languages } = storeToRefs(store)

/** 抽屉是否展开。 */
const open = ref(false)

const triggerRef = useTemplateRef<HTMLButtonElement>('trigger')
const panelRef = useTemplateRef<HTMLElement>('panel')
const closeRef = useTemplateRef<HTMLButtonElement>('close')

/** 打开时焦点进抽屉，关闭时还给触发按钮；等一帧让 v-if 的元素先挂上。 */
async function setOpen(next: boolean): Promise<void> {
  if (open.value === next) return
  open.value = next
  await nextTick()
  if (next) closeRef.value?.focus()
  else triggerRef.value?.focus()
}

/** 文件位的显示名；认不出的键直接显示键名。 */
const FILE_LABELS: Record<string, string> = {
  segments: '字幕段落 (JSON)',
  subtitles: '字幕文件 (SRT)',
  context: '主题与术语 (JSON)',
  terms: '术语表 (JSON)',
}

/** 按类型分组的产物，顺序跟后端给的一致（原始转写 → 清洗后字幕 → 译文）。 */
const groups = computed(() =>
  artifacts.value.map((artifact) => ({
    artifact,
    title: groupTitle(artifact),
    files: Object.entries(artifact.files).map(([key, file]) => ({
      key,
      label: FILE_LABELS[key] ?? key,
      url: file.url,
      sizeText: formatBytes(file.sizeBytes),
    })),
  })),
)

/** 分组标题：译文要带上语种名（认不出语种就显示码本身）。 */
function groupTitle(artifact: ArtifactResponse): string {
  const label = ARTIFACT_TYPE_LABEL[artifact.type]
  if (artifact.type !== ARTIFACT_TYPE.TRANSLATED) return label
  const language = languages.value.find((option) => option.code === artifact.lang)
  return `${label}（${language?.label ?? artifact.lang}）`
}

/** Esc 收起；Tab 在抽屉里循环，别跑到遮罩后面的主界面上。 */
function onDocumentKeydown(event: KeyboardEvent): void {
  if (!open.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    void setOpen(false)
    return
  }
  if (event.key !== 'Tab') return
  const panel = panelRef.value
  if (!panel) return
  const focusable = panel.querySelectorAll<HTMLElement>('a[href], button:not([disabled])')
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (!first || !last) return
  const active = document.activeElement
  if (!panel.contains(active)) {
    event.preventDefault()
    first.focus()
    return
  }
  if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  } else if (event.shiftKey && active === first) {
    event.preventDefault()
    last.focus()
  }
}

document.addEventListener('keydown', onDocumentKeydown)
onScopeDispose(() => document.removeEventListener('keydown', onDocumentKeydown))
</script>

<template>
  <button
    ref="trigger"
    type="button"
    class="rounded-lg border border-border bg-background px-3 py-1.5 text-sm text-ink transition-colors hover:bg-mist hover:text-foreground"
    aria-haspopup="dialog"
    :aria-expanded="open"
    @click="setOpen(!open)"
  >
    产物
  </button>

  <Teleport to="body">
    <!-- 遮罩与面板分开做过渡：一个淡、一个滑，合成在一个根元素上只能二选一 -->
    <Transition
      enter-active-class="transition-opacity duration-300 ease-out motion-reduce:transition-none"
      enter-from-class="opacity-0"
      leave-active-class="transition-opacity duration-200 ease-in motion-reduce:transition-none"
      leave-to-class="opacity-0"
    >
      <div
        v-if="open"
        class="fixed inset-0 z-40 bg-foreground/30"
        aria-hidden="true"
        @click="setOpen(false)"
      ></div>
    </Transition>

    <Transition
      enter-active-class="transition-transform duration-300 ease-out motion-reduce:transition-none"
      enter-from-class="translate-x-full"
      leave-active-class="transition-transform duration-200 ease-in motion-reduce:transition-none"
      leave-to-class="translate-x-full"
    >
      <section
        v-if="open"
        ref="panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="artifact-drawer-title"
        class="fixed inset-y-0 right-0 z-50 flex w-full flex-col border-l border-border bg-background shadow-xl sm:w-96"
      >
        <header
          class="flex h-14 shrink-0 items-center justify-between gap-3 border-b border-border px-4"
        >
          <h2 id="artifact-drawer-title" class="text-sm font-medium">产物</h2>
          <button
            ref="close"
            type="button"
            class="rounded-lg p-1.5 text-muted transition-colors hover:bg-mist hover:text-foreground"
            aria-label="关闭产物抽屉"
            @click="setOpen(false)"
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
              <path d="m6 6 12 12M18 6 6 18" />
            </svg>
          </button>
        </header>

        <div class="overlay-scrollbar min-h-0 flex-1 overflow-y-auto p-2">
          <p v-if="groups.length === 0" class="px-2 py-3 text-sm text-faint">暂无可下载的产物</p>
          <div v-for="group in groups" :key="group.artifact.type + group.artifact.lang" class="py-1">
            <p class="px-2 pb-1 text-xs font-medium text-muted">
              {{ group.title }} · rev {{ group.artifact.revision }}
            </p>
            <a
              v-for="file in group.files"
              :key="file.key"
              :href="file.url"
              download
              class="flex items-center justify-between gap-3 rounded-lg px-2 py-2 text-sm text-ink transition-colors hover:bg-mist"
            >
              <span class="min-w-0 truncate">{{ file.label }}</span>
              <span class="shrink-0 text-xs tabular-nums text-faint">{{ file.sizeText }}</span>
            </a>
          </div>
        </div>
      </section>
    </Transition>
  </Teleport>
</template>
