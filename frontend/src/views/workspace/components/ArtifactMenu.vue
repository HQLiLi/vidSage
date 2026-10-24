<script setup lang="ts">
// 顶栏的「产物」下拉：按产物类型分组列出可下载的文件。
// 下载地址用清单里给的 url（后端拼好的全路径），前端不自己拼路径。
import { computed, onScopeDispose, ref } from 'vue'
import { storeToRefs } from 'pinia'

import { ARTIFACT_TYPE } from '@/enums/subtitle'
import { useWorkspaceStore } from '@/stores/workspace'
import { ARTIFACT_TYPE_LABEL } from '@/utils/artifacts'
import { formatBytes } from '@/utils/format'
import type { ArtifactResponse } from '@/types/subtitle'

const store = useWorkspaceStore()
const { artifacts, languages } = storeToRefs(store)

/** 下拉是否展开。 */
const open = ref(false)
const rootRef = ref<HTMLElement | null>(null)

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

/** 点外面收起下拉。 */
function onDocumentClick(event: MouseEvent): void {
  if (!open.value) return
  const root = rootRef.value
  if (root && event.target instanceof Node && root.contains(event.target)) return
  open.value = false
}

document.addEventListener('click', onDocumentClick)
onScopeDispose(() => document.removeEventListener('click', onDocumentClick))
</script>

<template>
  <div ref="rootRef" class="relative shrink-0">
    <button
      type="button"
      class="rounded-lg border border-border bg-background px-3 py-1.5 text-sm text-ink transition-colors hover:bg-mist hover:text-foreground"
      :aria-expanded="open"
      aria-haspopup="true"
      @click="open = !open"
    >
      产物
    </button>

    <div
      v-if="open"
      class="absolute right-0 top-full z-30 mt-2 w-72 rounded-xl border border-border bg-background p-2 shadow-lg"
    >
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
          class="flex items-center justify-between gap-3 rounded-lg px-2 py-1.5 text-sm text-ink transition-colors hover:bg-mist"
        >
          <span class="min-w-0 truncate">{{ file.label }}</span>
          <span class="shrink-0 text-xs tabular-nums text-faint">{{ file.sizeText }}</span>
        </a>
      </div>
    </div>
  </div>
</template>
