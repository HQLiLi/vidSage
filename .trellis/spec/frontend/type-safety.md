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
import { useCounterStore } from '@/stores/counter'
```

## 类型定义放哪

| 场景 | 位置 |
|---|---|
| 单组件内部使用 | 组件 `<script setup>` 内直接定义 |
| 跨组件共享 | `src/types/`（**尚未创建**，出现共享需求时再建） |
| store 相关 | 与 store 同文件导出 |
| 后端接口契约 | 建 `src/types/` 后按功能分文件；**字段名与 Java record 对齐**（`beginTimeMs` 等保持同名） |

> 后端契约目前是 Java `record`（如 `TranscriptionSegment(beginTimeMs, endTimeMs, text)`）。前端镜像类型时**保持字段名一致**，便于对照排查。

## 类型写法

- 对象结构优先用 `interface`，联合类型/工具类型用 `type`
- 不要用 `any`；确实无法确定时用 `unknown` 再收窄
- 不要用 `as` 强转绕过类型错误——先确认类型定义是否写错了
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
| 用 `any` 或 `as` 消除报错 | 掩盖真实类型问题 |
| 只改 `tsconfig` 或只改 `vite.config.ts` 的别名 | 两处必须同步，否则构建与类型检查行为不一致 |
| 手机械复制后端契约时改字段名 | 破坏前后端对照 |
| 用相对路径跨目录导入 | 统一 `@/` |
