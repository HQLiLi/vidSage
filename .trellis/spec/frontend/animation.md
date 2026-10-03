# GSAP 动画规范

> 项目动画统一用 GSAP（`gsap ^3.15`，全插件免费版）。本文只写**项目层决策**；
> API 级用法（tween 参数、ease 全表、插件细节、性能手段）**以官方 agent skills 为准**：
> `.agents/skills/gsap-core` / `gsap-frameworks` / `gsap-performance` / `gsap-timeline` / `gsap-scrolltrigger`。

---

## 与官方 skills 的分层关系（防冲突约定）

本文条款分三类，与官方 skills 不对立：

1. **取自官方**：生命周期、reduced-motion、ScrollTrigger refresh 时机——原文级一致，此处成文仅为强制执行。
2. **官方原则的项目收紧**：统一导入点、性能分层、timeline 时序、动画钩子——同方向、更具体。
3. **显式覆盖官方默认**：缓动基线（见下文，已标注官方默认值）。

---

## 统一导入与注册

- `src/lib/gsap.ts` 是全项目**唯一**的 `gsap.registerPlugin()` 调用点。
- 组件一律 `import { gsap } from '@/lib/gsap'`，**禁止直接 `from 'gsap'`**。
- 启用插件时只改 `src/lib/gsap.ts`（`import + registerPlugin`），所有消费者自动生效。

```ts
// ✅ 正确
import { gsap } from '@/lib/gsap'

// ❌ 禁止：绕过统一入口
import { gsap } from 'gsap'
```

参照：`frontend/src/lib/gsap.ts`

## 生命周期（取自官方 gsap-frameworks）

三条硬规则：

1. **只在 `onMounted` 后创建** tween——禁止在 `<script setup>` 顶层直接建 tween（DOM 尚不存在）。
2. **`gsap.context()` 必传根元素 ref 作第二参数**（selector scope）——裸字符串选择器会命中组件外同名人元素。
3. **`onUnmounted` 必调 `ctx.revert()`**——杀死 tween 与 ScrollTrigger、还原内联样式，防泄漏与"幽灵动画"。

```vue
<script setup lang="ts">
import { onMounted, onUnmounted, useTemplateRef } from 'vue'
import { gsap } from '@/lib/gsap'

const root = useTemplateRef<HTMLElement>('root')
let ctx: gsap.Context | undefined

onMounted(() => {
  if (!root.value) return
  ctx = gsap.context(() => {
    gsap.from('[data-animate="card"]', { autoAlpha: 0, y: 16, duration: 0.4, stagger: 0.08 })
  }, root.value)
})

onUnmounted(() => ctx?.revert())
</script>
```

composable 内封装动效时，清理用 `onScopeDispose(() => ctx?.revert())`（与[组件规范](./component-guidelines.md)的副作用清理约定一致）。

## 动画钩子：`data-*` 属性，不依赖样式类名

动画目标用 `data-animate="xxx"`（或语义化 `data-*`）选择器，**不用 Tailwind 工具类名**当选择器——类名是样式实现细节，调样式不应破坏动效。

## reduced-motion（强制，取自官方 gsap.matchMedia）

系统开启"减少动态效果"的用户**必须**拿到完整可用的静态界面。GSAP 动效一律经 `gsap.matchMedia()` 出降级分支：

```ts
const mm = gsap.matchMedia()
mm.add('(prefers-reduced-motion: no-preference)', () => {
  // 完整动效只在此分支创建；不匹配时什么都不建，即静态降级
})
```

`mm` 在 context 内创建则随 `revert()` 一并清理。纯 CSS 过渡用 Tailwind 的 `motion-reduce:` 变体，同理由。

## 性能分层（对齐官方 gsap-performance 的 prefer/avoid，不比官方更严）

| 档 | 属性 | 说明 |
|---|---|---|
| **禁止** | `width` / `height` / `top` / `left` / `margin` / `padding` | 触发 layout，官方 Do Not 明令；位移/缩放一律 `x` / `y` / `scale`（尺寸动画用 scale 或 FLIP 变通） |
| **默认只用** | transform（`x` / `y` / `scale` / `rotation`）与 `opacity` | 走合成器，不触发 layout/重绘 |
| **允许** | `backgroundColor` 等颜色 / paint 属性 | 官方允许，按需使用，勿在大面积元素上高频触发 |
| **淡入淡出** | 用 `autoAlpha`，不用裸 `opacity` | `autoAlpha: 0` 同时置 `visibility: hidden`，不可见元素不再挡点击 |

## 初始态与 FOUC

入场动画的起始态用 `gsap.from()` / `fromTo()` / `gsap.set()` 写在 context 内，**不要在 CSS 里预写"待动画"的隐藏态**（如 `.card { opacity: 0 }`）——JS 失败或 reduced-motion 降级时页面仍完整可见。`from()` / `fromTo()` 默认 `immediateRender: true`，创建即应用起始态，天然防闪烁。

## 时序：timeline + labels

两段以上有先后关系的动画用 `gsap.timeline()` 编排，用 labels 标记章节；**禁止用一串递增的裸 `delay` 数字拼接**（改一个时间全盘手调）。单段动画的单一 `delay` 不受限。

```ts
// ❌ 禁止：递增 delay 拼接
gsap.to(a, { y: 0, delay: 0.2 })
gsap.to(b, { y: 0, delay: 0.55 })

// ✅ 正确：timeline + 相对位置
const tl = gsap.timeline()
tl.to(a, { y: 0 }).to(b, { y: 0 }, '-=0.15')
```

## 缓动与时长基线（第③类：显式覆盖官方默认）

| 场景 | ease | 时长 |
|---|---|---|
| 所有 UI 动效默认 | `power2.out`（**项目覆盖**；官方库默认 `power1.out`，在此显式改写） | 0.3–0.5s |
| 少量关键元素的强调入场 | `back.out(1.7)` | ≤ 0.6s，克制使用 |
| hover / 按压等微交互 | **不进 GSAP**，用 Tailwind 的 `transition-*` 工具类 | — |

统一基线的目的是防止每个开发者（和每次 AI 生成）凭口味自选；确需其他 ease 时在代码旁注释理由。

## ScrollTrigger 专项（取自官方 gsap-frameworks）

- 注册：在 `src/lib/gsap.ts` 统一 `gsap.registerPlugin(ScrollTrigger)`，组件从 `@/lib/gsap` 拿已注册的 gsap。
- 布局变化（数据加载、折叠展开、图片加载完）会改变触发位置：`await nextTick()` 后调 `ScrollTrigger.refresh()`。
- cleanup 随 context（`revert()` 已覆盖 ScrollTrigger 的 kill，无需单独处理）。

## 代码归属

- 页面私有动效留在页面子包内（组件内联或页面私有模块）。
- 第二个使用方出现才上移 `src/composables/`，命名 `useXxxAnimation`（见[目录结构](./directory-structure.md)与[组件规范](./component-guidelines.md)）。

## 边界：GSAP 只动视觉

禁止 tween Pinia state、表单值等数据层（官方支持 tween 任意 JS 对象，**此条是项目架构限制而非 API 限制**）——状态变更必须走 store action，tween 表单值会破坏 `v-model`。GSAP 的输入是 DOM/SVG，不是业务状态。

## 反模式

| 不要做 | 依据 |
|---|---|
| 组件里直接 `from 'gsap'` 或散装 `registerPlugin` | 统一导入点（本文第一节） |
| `<script setup>` 顶层直接建 tween | DOM 未挂载（生命周期硬规则 1） |
| `gsap.context(fn)` 不传根元素 | selector 泄漏到组件外（硬规则 2） |
| 只建 tween 不 `revert()` | 泄漏 + 幽灵动画（硬规则 3） |
| tween `width`/`top` 等布局属性 | 官方 Do Not，触发 layout |
| CSS 预写 `opacity: 0` 等待 JS 播放 | JS 失败 / 降级时页面空白 |
| 递增 `delay` 拼接时序 | 维护灾难，用 timeline |
| 无 reduced-motion 分支 | 本文强制条款 |
| tween store state / 表单值 | 数据层边界 |
