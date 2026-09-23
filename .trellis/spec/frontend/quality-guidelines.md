# 代码质量规范

---

## 一、不重复造轮子（项目级硬性要求）

**有成熟的主流库/插件就用它，不要手搓。**

这条是项目所有者的明确规定，前后端都适用。前端已有代码的体现：

- **构建**：Vite，不手写打包脚本
- **状态管理**：Pinia，不自己写全局事件总线或 `reactive` 单例
- **样式**：Tailwind + SCSS，不手写原子类系统
- **代码检查**：ESLint + oxlint，不手写 lint 脚本
- **格式化**：Prettier，不手工对齐格式
- **构建/检查编排**：`npm-run-all2` 的 `run-s` / `run-p`（见 `package.json` 的 `lint` 与 `build`），不自己写 shell 串联

### 判断标准

| 场景 | 做法 |
|---|---|
| 有主流库覆盖 | **直接用** |
| 库需要少量适配 | 包一层，**不要因此重写整个功能** |
| 确实没有合适轮子 | 才自己写，并说明为何不用现成方案 |

---

## 二、注释要求（项目级硬性要求）

**每个函数/方法都要写注释。**

### 前端怎么写

- 语言：**中文**（与后端代码注释一致）
- 导出的函数、composable、store action、工具函数：**必须有注释**
- 注释说明**做什么、有什么约束**，不要复述函数名
- 简单到自解释的一行 getter 可省，但**凡有分支、副作用、边界条件的必须写**

```ts
/**
 * 提交视频转写任务并返回任务 id。
 * 同一视频重复提交会返回已有任务，不会重复计费。
 */
export async function submitTranscription(videoId: string): Promise<string> {
  // ...
}
```

> 现有 `src/stores/counter.ts` 的 `increment()` 无注释，属脚手架遗留。**新增代码必须写**，不要以旧代码为准。

---

## 三、双 linter 机制（重要，别踩）

项目**同时用 oxlint 和 ESLint**，各有分工：

```json
"lint": "run-s \"lint:*\"",
"lint:oxlint": "oxlint . --fix",
"lint:eslint": "eslint . --fix --cache"
```

**先 oxlint 再 eslint**（`run-s` 是串行）。

### 分工

| 工具 | 职责 | 配置 |
|---|---|---|
| **oxlint** | 快速规则检查（correctness 类） | `.oxlintrc.json` |
| **ESLint** | Vue + TypeScript 语义规则 | `eslint.config.ts` |

### 关键：规则不重复

`eslint.config.ts` 里有这一行：

```ts
...pluginOxlint.buildFromOxlintConfigFile('.oxlintrc.json'),
```

它**读 `.oxlintrc.json` 并自动关闭 ESLint 中重复的规则**，避免同一问题报两次。

**推论：**

- 新增规则时**先判断该放哪个 linter**——纯语法/correctness 类放 `.oxlintrc.json`；涉及 Vue 模板语义或 TS 类型感知的放 `eslint.config.ts`
- **不要**在两处同时启用同一规则

### oxlint 配置要点

```json
{
  "plugins": ["eslint", "typescript", "unicorn", "oxc", "vue"],
  "env": { "browser": true },
  "categories": { "correctness": "error" }
}
```

只开了 `correctness` 分类且为 `error` 级别——**不开启风格类规则**（风格交给 Prettier）。

### Prettier 必须放最后

```ts
import skipFormatting from 'eslint-config-prettier/flat'
// ...
skipFormatting,
```

`skipFormatting` **必须是 `eslint.config.ts` 数组的最后一项**，否则格式化规则冲突。

## 四、格式化

`.prettierrc.json`：

```json
{
  "semi": false,
  "singleQuote": true,
  "printWidth": 100
}
```

| 项 | 值 | 含义 |
|---|---|---|
| `semi` | `false` | **不写分号** |
| `singleQuote` | `true` | **用单引号** |
| `printWidth` | `100` | 行宽 100 |

`.editorconfig` 补充：

- 缩进 **2 空格**
- 换行符 **LF**
- UTF-8、文件末尾留空行、去除行尾空白
- `max_line_length = 100`

**不要手工调格式，跑 `npm run format`。**

## 五、导入顺序

`eslint.config.ts` 中 `globalIgnores(['**/dist/**', '**/dist-ssr/**', '**/coverage/**'])`，注意 `dist/` 是构建产物，**不要手工编辑**。

导入分组跟随现有代码（`main.ts`、`App.vue`）：

1. 样式入口
2. 第三方库（`vue`、`pinia`）
3. 本地模块（`@/...`）

每组之间空一行。

## 六、验证命令

**提交前必须跑通：**

```bash
npm run type-check   # vue-tsc --build，类型检查
npm run lint         # oxlint + eslint
```

`npm run build` 会并行跑 `type-check` 与 `build-only`，也可作为完整验证。

## 七、反模式清单

| 不要做 | 依据 |
|---|---|
| 手写工具函数替代成熟库 | 「不重复造轮子」要求 |
| 导出函数不写注释 | 「函数方法要写注释」要求 |
| 把 `skipFormatting` 放到 `eslint.config.ts` 数组中间 | 必须最后一项 |
| 同一规则同时配在 oxlint 与 ESLint | 双重报错 |
| 手工加换行/分号/双引号 | 与 Prettier 配置冲突 |
| 编辑 `frontend/dist/` 下的文件 | 构建产物，且已被 ignore |
| 提交前不跑 `type-check` | 严格模式下的类型错误会漏到构建期 |
