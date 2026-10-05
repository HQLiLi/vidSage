<script setup lang="ts">
// Hero 声波横幅（纯装饰）：深绿渐变天空 + 90 根确定性伪随机波形，与工作区
// 音频卡片波形同一视觉语言。无 props / 无事件 / 无响应式状态；显示尺寸由
// 调用方经 class 透传到 svg 根，组件只负责 viewBox 几何与绘制。

/** 横幅的单根波形：装饰性列表，位置序号即身份（作 v-for key）。 */
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

// 波形几何常量：viewBox 720x200，90 根 * 8px 间距恰好铺满，基线居中于 y=100
const WAVE_BAR_COUNT = 90
const WAVE_BAR_GAP = 8
const WAVE_BASELINE = 100
const WAVE_MIN_HEIGHT = 8
const WAVE_AMPLITUDE = 66

/**
 * 生成横幅的 90 根波形：三个不同频率 / 相位的正弦叠加取绝对值，输出确定性
 * 伪随机高度（8–74px）。纯静态装饰，无响应式依赖，实例化时计算一次。
 */
function buildWaveBars(): WaveBar[] {
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
}

const waveBars = buildWaveBars()
</script>

<template>
  <svg viewBox="0 0 720 200" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
    <defs>
      <linearGradient id="hero-wave-sky" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" class="wave-sky-top" />
        <stop offset="1" class="wave-sky-bottom" />
      </linearGradient>
      <radialGradient id="hero-wave-glow" cx="0.82" cy="0.3" r="0.9">
        <stop offset="0" class="wave-glow-strong" />
        <stop offset="1" class="wave-glow-faint" />
      </radialGradient>
      <linearGradient id="hero-wave-shade" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0.45" class="wave-shade-clear" />
        <stop offset="1" class="wave-shade-deep" />
      </linearGradient>
      <linearGradient id="hero-wave-fade" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0" class="wave-fade-deep" />
        <stop offset="1" class="wave-fade-clear" />
      </linearGradient>
      <filter id="hero-wave-grain">
        <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" stitchTiles="stitch" />
        <feColorMatrix type="matrix" values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 0.7 0" />
      </filter>
    </defs>
    <rect width="720" height="200" fill="url(#hero-wave-sky)" />
    <rect width="720" height="200" fill="url(#hero-wave-glow)" />
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
    <rect width="340" height="200" fill="url(#hero-wave-fade)" />
    <rect width="720" height="200" fill="url(#hero-wave-shade)" />
    <rect width="720" height="200" filter="url(#hero-wave-grain)" opacity="0.05" />
  </svg>
</template>

<style scoped lang="scss">
/*
 * 渐变 stop 色值没有对应的 Tailwind 工具类，走 scoped SCSS + CSS 变量，引用
 * @/assets/tailwind.css 的 @theme 设计令牌；明暗渐变里的黑阶统一用 foreground
 * 令牌而非裸 #000，维持「组件只引用 token」的约束。
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
</style>
