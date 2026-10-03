# 前端引入 GSAP 与 axios 基础设施及规范沉淀

## Goal

前端安装 gsap 与 axios，建立 `src/lib/gsap.ts` 集中注册与 `src/api/request.ts` 实例，安装 GreenSock 官方 agent skills，沉淀 `animation.md` 与 `api.md` 两份前端规范并更新目录规范。

## Background

- 前端当前是全新骨架（Vue 3.5 + TS 6 + Vite 8 + Tailwind 4），**零网络代码、零动画依赖**。
- 后端（Java Spring）无统一响应包装：直接返回 `TaskView` 等 DTO，语义靠 HTTP 状态码（202/400/409，409 响应体附既有任务视图）；SSE 端点 `GET /api/tasks/{taskNo}/events`；服务端口为 Spring 默认 8080（无 server.port 覆盖）。
- 技术选型经 grill-me 逐条定案（2026-10-03 会话），决策记录在下方"约束"。

## Requirements

### R1 GSAP 安装与基建
- `frontend/` 安装 `gsap`（^3.13，全插件免费版）。**不装 `@gsap/react`**（React 专用）。
- 新建 `src/lib/gsap.ts`：全项目唯一 `gsap.registerPlugin()` 入口，re-export 配置好的 gsap 实例；组件一律 `import { gsap } from '@/lib/gsap'`，禁止直接 `from 'gsap'`。
- 插件按需 `import + registerPlugin`，当前无使用场景则不预注册。

### R2 官方 agent skills 安装
- 安装 GreenSock 官方 `greensock/gsap-skills` 中与本项目相关的 5 个：`gsap-core`、`gsap-frameworks`、`gsap-performance`、`gsap-timeline`、`gsap-scrolltrigger`；**不装** `gsap-react`、`gsap-utils`。
- Vendoring 到项目 `.agents/skills/`（进 git、跨平台），保留原目录名与 MIT LICENSE。

### R3 animation.md（GSAP 项目规范）
新建 `.trellis/spec/frontend/animation.md`，定位为**项目决策层**，API 级用法以官方 skills 为准（文中给指针），不重复维护。

> **与官方 skills 的分层关系（防冲突约定）**：条款分三类——① **取自官方**（生命周期、reduced-motion、refresh 时机）：原文级一致；② **官方原则的项目收紧**（统一导入点、性能分层、timeline 时序）：同方向、更具体，无对立；③ **显式覆盖官方默认**（缓动基线）：文中必须标注"官方默认值"，避免读者误以为官方如此。

须包含：
- 统一导入点（`@/lib/gsap`）与注册约定（是官方"register once per app"的具体化：re-export 同一模块单例，不产生第二实例）
- 统一导入点（`@/lib/gsap`）与注册约定
- 生命周期官方模式：`onMounted` 内 `gsap.context(fn, 根元素ref)`、`onUnmounted` 内 `ctx.revert()`；**禁止在 setup 顶层建 tween**；selector 必须传 scope 容器
- `prefers-reduced-motion` **强制**：用 `gsap.matchMedia()` 出无动画降级分支
- 性能分层（对齐官方 gsap-performance 的 prefer/avoid 措辞，不比官方更严）：**禁止** tween layout 属性（`width`/`height`/`top`/`left`/`margin`/`padding`——官方 Do Not 明令，位移一律 `x`/`y`/`scale`）；默认只用 transform/opacity；`backgroundColor` 等颜色/paint 属性官方允许、按需可用；淡入淡出用 `autoAlpha` 而非裸 `opacity`
- 初始态用 `gsap.set()` / `fromTo()` 写在 context 内，不在 CSS 预写隐藏态
- 多段时序用 `timeline` + labels，禁裸 `delay` 拼接
- 缓动基线：默认 `power2.out`、时长 0.3–0.5s（注意官方库默认是 `power1.out`，本条是项目覆盖）
- ScrollTrigger 专项：布局变化后 `nextTick` 再 `ScrollTrigger.refresh()`（经统一入口触发）
- 代码归属：页面私有动效留页面子包，跨页复用上移 `composables/`（`useXxxAnimation` 命名）
- 边界：GSAP 只动视觉，禁止 tween Pinia state / 表单值等数据层（注：官方支持 tween 任意 JS 对象，此条是项目架构限制而非 API 限制——状态变更必须走 action，tween 表单值会破坏 v-model）

### R4 axios 与 api 包
- `frontend/` 安装 `axios`。
- 新建 `src/api/`：
  - `request.ts`（用户指定命名）：创建并导出唯一 axios 实例——不设 baseURL（同源相对路径），预设超时与响应/错误拦截器骨架；
  - `types.ts`：与后端 DTO 对齐的类型（`TaskView`、`SubmitUrlRequest` 等），类型就近放 api 包内；
  - 一域一文件：`tasks.ts` 覆盖 `TaskController` 全部非 SSE 端点（提交 URL、上传、查任务、触发翻译），`media.ts` 覆盖 `/api/media/tools`；每个文件导出类型化函数（`Promise<TaskView>` 等），上传走 `FormData`；
  - **不建 barrel**，导入写具体文件 `@/api/tasks`（与 stores 约定同风格）。
- **SSE 不进 api 包**（axios 不支持流），后续接 SSE 走 `composables/`（EventSource 或 fetch）。

### R5 Vite dev 代理
- `vite.config.ts` 增加 `server.proxy`：`/api` → `http://localhost:8080`。
- 不引入 `VITE_API_BASE_URL` 环境变量；分域部署需求出现时再加。

### R6 spec 同步更新
- 新建 `.trellis/spec/frontend/api.md`（轻量）：request.ts 实例唯一、一域一文件、类型就近、**无 envelope 的错误处理约定**（基于 HTTP 状态码；409 响应体取既有任务视图）、SSE 边界、导入写具体文件。
- 更新 `index.md`：规范索引表加 `animation.md`、`api.md` 两行；技术栈表加 gsap 与 axios 版本。
- 更新 `directory-structure.md`：结构示意与目录职责表加 `src/lib/`、`src/api/`。

## 约束（grill-me 定案，不再重开）

1. 只装 `gsap` 主包与 `axios`，不加其他动画/请求库。
2. `src/lib/gsap.ts`（Q5=A，用户推翻了最初的 composables 方案）；`src/api/request.ts` 命名为用户指定（Q9）。
3. reduced-motion 为强制级（Q6=A）。
4. 官方 skills 双轨策略：skills 管 API 正确性，animation.md 只写项目决策（Q8=A）。
5. 同源相对路径 + dev 代理（Q10=A）。

## Acceptance Criteria

- [x] `cd frontend && npm run type-check && npm run lint` 全绿。（2026-10-03 实测通过）
- [x] `src/lib/gsap.ts` 存在且导出配置好的 gsap；全仓 `registerPlugin` 调用点唯一。（当前无插件使用场景，仅注释约定，符合"不预注册"）
- [x] `src/api/request.ts` 导出 axios 实例；`tasks.ts` / `media.ts` 函数与后端 DTO 字段对齐（逐字段镜像 `TaskView` / `SubmitUrlRequest` / `TaskConflictResponse` / `ExternalToolReport`）。
- [x] `vite.config.ts` 代理生效：dev server 下 `/api/*` 转发到 `localhost:8080`（实测 `/api/media/tools` → 502，代理已接管、后端未启动；对照 `/` → 200 SPA 不经代理）。
- [x] `.agents/skills/` 下存在 5 个 `gsap-*` 目录，各含完整 SKILL.md；LICENSE 保留（在 gsap-core/）。
- [x] `animation.md`、`api.md` 落盘且被 `index.md` 索引；`directory-structure.md` 覆盖 `src/lib/`、`src/api/`；`type-safety.md` 契约类型行同步改为 `src/api/types.ts`。
- [x] `animation.md` 包含官方三条硬规则：selector 必传 scope 容器、禁止 setup 顶层创建 tween、布局变化后 `nextTick` refresh。
