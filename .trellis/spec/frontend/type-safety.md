# 类型规范

> 项目在标准 Vue 脚手架配置上**额外开了一个严格选项**，并且全面使用路径别名。
> 本文说明这两点带来的实际约束。

---

## TS 配置来源

应用代码的编译选项在 `frontend/tsconfig.app.json`：

```json
{
  "extends": "@vue/tsconfig/tsconfig.dom.json",
  "include": ["env.d.ts", "src/**/*", "src/**/*.vue"],
  "exclude": ["src/**/__tests__/*"],
  "compilerOptions": {
    "noUncheckedIndexedAccess": true,
    "paths": { "@/*": ["./src/*"] },
    "tsBuildInfoFile": "./node_modules/.tmp/tsconfig.app.tsbuildinfo"
  }
}
```

基线严格度来自 `@vue/tsconfig`（`strict: true` 等），**不要在这些基础上放宽**。

## `noUncheckedIndexedAccess: true` —— 最容易踩的坑

这是本项目**额外开启**的（不在 `@vue/tsconfig` 默认里）。它让数组和对象的索引访问返回 `T | undefined`。

```ts
const items: string[] = ['a', 'b']

// ❌ 类型错误：items[0] 的类型是 string | undefined
const first: string = items[0]

// ✅ 先判空
const first = items[0]
if (first !== undefined) {
  console.log(first.toUpperCase())
}

// ✅ 或给兜底值
const first = items[0] ?? ''
```

同理适用于对象索引、`Record<K, V>` 的取值、正则匹配组等。

配置里的注释也写明了取舍：

> Extra safety for array and object lookups, but may have false positives.

**遇到 `可能为 undefined` 的报错时，正确做法是加判空或兜底，不是关掉这个选项。**

## 路径别名

`@/*` → `./src/*`，在 `tsconfig.app.json` 的 `paths` 与 `vite.config.ts` 的 `resolve.alias` **两处**都有配置，改一处必须同步另一处。

```ts
import router from '@/routes'
```

## TypeScript 类型规范

创建与使用类型的操作口径（2026-10-05 定调）：

- 创建 `type` / `interface` 前，先搜索现有类型定义
- 优先复用 `src/types`、模块内 `types.ts` 中的类型
- 仅当前文件使用：局部定义，不加 `export`
- 模块内共享：放模块 `types.ts`，加 `export`
- 跨模块共享：放 `src/types`，加 `export`
- 组件 Props / Emits 类型不加 `export`
- 不要重复定义语义相同的类型
- 纯类型导入使用 `import type`

## 类型定义放哪（两级类型策略，2026-10-03 定调）

| 场景 | 位置 |
|---|---|
| 单组件内部使用 | 组件 `<script setup>` 内直接定义 |
| **跨模块共享**（契约镜像、跨模块视图模型） | 全局 `src/types/`，按域一文件（`task.ts` / `subtitle.ts` / `media.ts`）；**准入门槛 = 跨模块使用**，不是「类型就该集中」 |
| 仅当前文件 / 模块内共享 | 仅当前文件：就地内联，**不加 `export`**；模块内共享：模块内 `types.ts`（成规模扩为 `types/`，如 `api/types/`），加 `export`；推断/派生类型（`ReturnType` 等）跟随源模块 |
| store 相关 | 与 store 同文件；有跨模块消费才导出 |
| 后端接口契约 | `src/types/task.ts`（任务与执行）/ `src/types/subtitle.ts`（产物、字幕、语种）/ `src/types/media.ts`（工具诊断）；字段名与 Java record / 枚举对齐，镜像规则见 [api.md](./api.md) |
| 运行时枚举值（有比较消费） | `src/enums/`（`as const` 对象）。**这不限于后端契约枚举**：后端枚举镜像、前端视图状态都放这里；契约类型的联合在 `types/` 由 `typeof` 派生，页面局部状态的联合可以在消费它的模块（如 store）里派生 |

> 后端契约目前是 Java `record`（如 `TranscriptionSegment(beginTimeMs, endTimeMs, text)`）。前端镜像类型时**保持字段名一致**，便于对照排查。

## 类型写法

- 对象结构优先用 `interface`，联合类型/工具类型用 `type`
- 有**比较消费**的枚举值：运行时值放 `src/enums/`（`as const` 对象）——后端契约枚举（例 `enums/task.ts` 的 `TASK_RUN_STATUS`、`enums/subtitle.ts` 的 `ARTIFACT_TYPE`）与前端视图状态（例 `enums/workspace.ts` 的 `WORKSPACE_PHASE`）都算。契约类型的联合在 `types/` 经 `import type` + `typeof` 派生（例 `TaskRunStatus`、`ArtifactType`）；页面局部状态的联合在消费它的模块里派生（例 `stores/workspace.ts` 的 `WorkspacePhase`）。`types/` 保持纯类型、构建期可整体擦除，比较处用命名常量不用裸字面量；不用 TS `enum` 语法。无比较消费的联合保持裸 `type`
- `export` 即对外契约承诺，无消费方的导出会虚增公共面：仅当前文件使用的类型（含只被同文件其他类型引用的从属类型，如 `RunResponse` 之外的从属联合）一律不导出；各档定义位置与导出口径以「TypeScript 类型规范」为准
- 不要用 `any`；确实无法确定时用 `unknown` 再收窄
- 不要用 `as` 强转绕过类型错误——先确认类型定义是否写错了。**唯一例外**（2026-10-03 定调）：`src/api/request.ts` 类型化门面对 axios 方法返回值做 1 处 `as Promise<T>` 受控断言——axios ^1.19 的返回值是条件类型 `AxiosResponseResult`，第二个泛型位传泛型 T 时条件悬置，与泛型调用点的 `Promise<T>` 标注冲突；而运行时成功拦截器已统一解包 data，门面在唯一边界处掰直返回类型。断言只许出现在该文件门面边界，调用点仍零 `as`
- 函数返回值显式标注（尤其是导出的函数）

## `env.d.ts`

根目录 `env.d.ts` 声明 Vite 与 Vue 的环境类型，已在 `tsconfig.app.json` 的 `include` 中。新增 Vite 环境变量类型时在 `src/env.d.ts` 或该文件中补充。

## 类型检查命令

```bash
npm run type-check   # vue-tsc --build
```

`tsconfig.app.json` 中配置了 `tsBuildInfoFile`，把增量构建缓存放进 `node_modules/.tmp/`，**不要**把它改回项目根目录（会污染仓库）。

## 反模式

| 不要做 | 原因 |
|---|---|
| 关掉 `noUncheckedIndexedAccess` 或加 `// @ts-ignore` 绕过 | 该项目显式开启，属于有意取舍 |
| 用 `any` 或 `as` 消除报错 | 掩盖真实类型问题（唯一例外：request.ts 门面边界的受控断言，见「类型写法」） |
| 只改 `tsconfig` 或只改 `vite.config.ts` 的别名 | 两处必须同步，否则构建与类型检查行为不一致 |
| 手机械复制后端契约时改字段名 | 破坏前后端对照 |
| 用相对路径跨目录导入 | 统一 `@/` |
| 仅当前文件使用却加 `export` 的 type / interface | 导出即对外契约，口径见「TypeScript 类型规范」 |
