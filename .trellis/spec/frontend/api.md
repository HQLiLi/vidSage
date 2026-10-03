# API 通信规范

> 前后端 HTTP 通信统一走 axios（`axios ^1.20`），代码在 `src/api/`。
> 契约事实源：`backend/src/main/java/com/vidsage/web/controller/` 与 `com/vidsage/model/`。

---

## 唯一实例

- `src/api/request.ts` 创建并导出唯一 axios 实例，**禁止在别处 `axios.create` 或直接用裸 `axios.get`**。
- SSE（`GET /api/tasks/{taskNo}/events`）**不走 axios**（不支持流）：接入时放 `src/composables/`，用 EventSource / fetch，不进 `src/api/`。

## 同源相对路径

- 请求路径一律写 `/api/...`，实例**不设 baseURL**。
- dev 由 `vite.config.ts` 的 `server.proxy` 把 `/api` 转发到 `localhost:8080`；生产由部署层反代。
- **不引入 `VITE_API_BASE_URL`**——分域部署需求真出现时再加。

## 域文件组织（与 stores 同风格）

| 文件 | 放什么 |
|---|---|
| `request.ts` | 唯一 axios 实例 + 拦截器（扩展点） |
| `tasks.ts` | 任务域端点（提交 / 上传 / 查询 / 触发翻译） |
| `media.ts` | 媒体域端点（工具诊断） |

- **一域一文件**，新域加新文件；**不建 `index.ts` barrel**。
- 导入写具体文件：`import { getTask } from '@/api/tasks'`。

## 契约类型（`src/types/`，两级类型策略）

契约类型**不在 api/ 内**（2026-10-03 定调）：全局共享类型独立在 `src/types/`（按域一文件：`task.ts` / `media.ts`），api 域文件从 `@/types/<域>` 导入。两级策略与准入门槛见 [type-safety.md](./type-safety.md)。

- 与后端 Java `record` / 枚举**逐字段镜像，字段名保持一致**（如 `beginTimeMs` 不改驼峰外的任何拼写），便于对照排查——依据 [type-safety.md](./type-safety.md)。
- 后端**无 `{code, data}` 包装**：响应体即裸 DTO，类型直接标注在泛型上（`request.get<TaskView>`）。
- 时间字段是 ISO-8601 字符串（Spring Boot 对 `LocalDateTime` 的默认序列化），前端类型写 `string`。
- 可空字段显式写 `| null`（`translationStatus: TranslationStatus | null`），与后端 `null` 语义对齐，不用 `?` 可选属性表达。

## 错误处理（无 envelope，状态码即语义）

| 状态码 | 语义 | 响应体 |
|---|---|---|
| 202 | 任务受理（提交 / 上传 / 触发翻译） | `TaskView` |
| 400 | 参数缺失（url 空、文件空） | 错误说明 |
| 404 | taskNo 不存在 | 错误说明 |
| 409 | 同 URL / 同内容已有进行中任务 | `TaskConflictResponse`（**含既有任务视图**，直接拿 `existingTask.taskNo` 继续跟进，不视为纯错误） |

- 拦截器只做**透传**（骨架见 `request.ts`），调用方按 `error.response?.status` 分支。
- 全局 toast / 埋点 / 401 处理将来加在 `request.ts` 拦截器，不改各调用方。

## 反模式

| 不要做 | 依据 |
|---|---|
| 组件里直接 `axios.get(...)` 或自建实例 | 唯一实例（request.ts） |
| 给 axios 设 baseURL 或引 `VITE_API_BASE_URL` | 同源相对路径约定 |
| 把 SSE 请求写进 `src/api/` | SSE 边界（走 composables） |
| 前端类型改后端字段名 | 契约镜像要求（type-safety.md） |
| 把 409 当纯错误弹窗，丢掉 `existingTask` | 409 响应体含可继续跟进的既有任务 |
| 建 `@/api` barrel 导入 | 一域一文件、具体文件导入 |
