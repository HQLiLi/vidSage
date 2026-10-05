<script setup lang="ts">
// 音频卡的进度波形（装饰 + seek 交互）：确定性伪随机高度的双正弦叠加，与上传页
// hero 横幅同一视觉语言。组件只负责几何与绘制：点亮进度由调用方经 progress（0–1）
// 驱动，点击位置以横轴比例（0–1）经 seek 事件回抛，秒数换算留在媒体侧。
import { computed } from 'vue'

/** 单根波形的归一化高度（0.16–1）。 */
interface WaveBar {
  /** 位置序号即身份：静态装饰列表永不重排，作 v-for key。 */
  id: number
  /** 归一化高度比例 */
  ratio: number
}

// 波形几何常量：viewBox 288x64，72 根 * 4px 步进铺满，振幅 56（上下各留 4px）
const WAVE_BAR_COUNT = 72
const WAVE_STEP = 4
const WAVE_AMPLITUDE = 56
const WAVE_MIN_RATIO = 0.16

const props = defineProps<{
  /** 播放进度比例（0–1）：按比例点亮已播放波形；时长未知时传 0。 */
  progress: number
}>()

const emit = defineEmits<{
  /** 点击波形 seek：携带点击位置的横轴比例（0–1），秒数换算由调用方完成。 */
  seek: [ratio: number]
}>()

/**
 * 生成 72 根波形：双正弦叠加取绝对值输出确定性高度（0.16–1）。纯静态装饰，
 * 无响应式依赖，实例化时计算一次。
 */
function buildWaveBars(): WaveBar[] {
  const bars: WaveBar[] = []
  for (let i = 0; i < WAVE_BAR_COUNT; i++) {
    const value = Math.abs(Math.sin(i * 5.7) * 0.5 + Math.sin(i * 2.3 + 0.8) * 0.5)
    bars.push({ id: i, ratio: WAVE_MIN_RATIO + value * (1 - WAVE_MIN_RATIO) })
  }
  return bars
}

const waveBars = buildWaveBars()

/** 已播放的波形根数：进度比例折算（四舍五入到根）。 */
const filledCount = computed(() => Math.round(props.progress * WAVE_BAR_COUNT))

/** 点击横坐标折算为 0–1 比例上抛；秒数换算与时长未知时的忽略逻辑由调用方完成。 */
function onWaveClick(event: MouseEvent): void {
  const target = event.currentTarget
  if (!(target instanceof Element)) return
  const rect = target.getBoundingClientRect()
  emit('seek', (event.clientX - rect.left) / rect.width)
}
</script>

<template>
  <svg
    class="cursor-pointer"
    viewBox="0 0 288 64"
    preserveAspectRatio="none"
    role="img"
    aria-label="播放进度波形"
    @click="onWaveClick"
  >
    <rect
      v-for="(bar, index) in waveBars"
      :key="bar.id"
      :x="index * WAVE_STEP"
      :y="(64 - bar.ratio * WAVE_AMPLITUDE) / 2"
      width="2.6"
      :height="bar.ratio * WAVE_AMPLITUDE"
      rx="1.3"
      :class="index < filledCount ? 'fill-primary' : 'fill-border'"
    />
  </svg>
</template>
