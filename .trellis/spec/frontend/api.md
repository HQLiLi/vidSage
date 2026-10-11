# API 通信规范

> 前后端 HTTP 通信统一走 axios（`axios ^1.20`），代码在 `src/api/`。
> 契约事实源：`backend/src/main/java/com/vidsage/web/controller/` 与 `com/vidsage/model/`。

---

## 唯一实例

- `src/api/request.ts` 持有唯一 axios 实例（内部，不导出）+ 类型化门面 `request`（get/post 已解包 data、错误归一化 ApiError；不导出裸实例，扩展方法在 request.ts 内按同款签名追加），**禁止在别处 `axios.create` 或直接用裸 `axios.get`**。
- SSE（`GET /api/tasks/{taskNo}/events`）**不走 axios**（不支持流）：接入时放 `src/composables/`，用 EventSource / fetch，不进 `src/api/`。

## SSE 订阅的错误处理（2026-10-11 立）

入口在 `src/composables/useTaskEvents.ts`。EventSource 的 `onerror` **不带状态码**，唯一可用的判别依据是 `readyState`，据此分两类：

| 情形 | `readyState` | 浏览器行为 | 前端表现 |
|---|---|---|---|
| 非 200（如 404）/ content-type 不符 | `CLOSED` | fail，**不再重连** | **致命**：关流，由消费方重拉一次 `GET /api/tasks/{taskNo}` 确诊——404 走「任务不存在」卡，其余走通用错误卡 |
| 网络断 / 后端重启 | `CONNECTING` | 自动重连（约 3s） | **瞬时**：不遮挡进度的「连接中断，正在重连…」提示；连上即消失，**不设重连上限** |

- **终态判据不在前端重编码**：SSE 事件里的 `terminal` 字段由后端算好送出（判据单源在后端状态机），前端读它即可，不要再拼「主状态终态 **且** 翻译非 RUNNING」这类组合。
- 重连后靠后端在连接建立时推的 DB 快照恢复进度，前端不自己攒状态。
- 连续失败到第几次才亮提示（防闪阈值）是实现细节，写在 composable 里，不进本规范。

## 同源相对路径（baseURL 统一前缀）

- 实例 baseURL 统一前缀来自 `VITE_API_BASE_URL`（默认回退 `/api`，env 见 `frontend/.env`），request 衍生调用点写相对路径（如 `/tasks`），不带 `/api` 前缀（2026-10-03 定调引入）。
- SSE 与 `<a download>` **不走 axios、吃不到 baseURL**，仍写全路径 `/api/...`——刻意保留的双拼法，非遗漏。
- dev 由 `vite.config.ts` 的 `server.proxy` 把 `/api` 转发到 `localhost:8080`；生产由部署层反代。

## 域文件组织（与 stores 同风格）

| 文件 | 放什么 |
|---|---|
| `request.ts` | 唯一 axios 实例（内部持有，不导出）+ 类型化门面（get/post 返回已解包 data）+ 响应拦截器（成功解包 / 错误归一化 `ApiError`；全局 toast / 埋点 / 401 的扩展点，在本文件 instance 上追加） |
| `tasks.ts` | 任务域端点（提交 / 上传 / 查询 / 触发翻译 / 产物 JSON 拉取） |
| `media.ts` | 媒体域端点（工具诊断） |

- **一域一文件**，新域加新文件；**不建 `index.ts` barrel**。
- 导入写具体文件：`import { getTask } from '@/api/tasks'`。

## 契约类型（`src/types/`，两级类型策略）

契约类型**不在 api/ 内**（2026-10-03 定调）：全局共享类型独立在 `src/types/`（按域一文件：`task.ts` / `media.ts`），api 域文件从 `@/types/<域>` 导入。两级策略与准入门槛见 [type-safety.md](./type-safety.md)。

- 与后端 Java `record` / 枚举**逐字段镜像，字段名保持一致**（如 `beginTimeMs` 不改驼峰外的任何拼写），便于对照排查——依据 [type-safety.md](./type-safety.md)。
- 后端**无 `{code, data}` 包装**：响应体即裸 DTO，类型直接标注在泛型上（`request.get<TaskView>`）；拦截器已统一解包，调用点直接拿到 `TaskView`，**不再手写 `.data`**。
- 时间字段是 ISO-8601 字符串（Spring Boot 对 `LocalDateTime` 的默认序列化），前端类型写 `string`。
- 可空字段显式写 `| null`（`translationStatus: TranslationStatus | null`），与后端 `null` 语义对齐，不用 `?` 可选属性表达。

## 错误处理（无 envelope，状态码即语义）

| 状态码 | 语义 | 响应体 |
|---|---|---|
| 202 | 任务受理（提交 / 上传 / 触发翻译） | `TaskView` |
| 400 | 参数缺失（url 空、文件空） | 错误说明 |
| 404 | taskNo 不存在 | 错误说明 |
| 409 | 同 URL / 同内容已有进行中任务 | `TaskConflictResponse`（**含既有任务视图**，直接拿 `existingTask.taskNo` 继续跟进，不视为纯错误） |

- 响应错误拦截器把错误归一化为 `ApiError`（status / message / existingTask，见 request.ts），调用点 catch 后按字段分支，不再自行拆 axios 错误。
- 全局 toast / 埋点 / 401 处理将来加在 `request.ts` 拦截器，不改各调用方。

## 反模式

| 不要做 | 依据 |
|---|---|
| 组件里直接 `axios.get(...)` 或自建实例 | 唯一实例（request.ts） |
| request 调用点写全路径 `/api/...`（baseURL 已统一前缀，会双重拼接） | baseURL 统一前缀约定 |
| 把 SSE 请求写进 `src/api/` | SSE 边界（走 composables） |
| 前端类型改后端字段名 | 契约镜像要求（type-safety.md） |
| 把 409 当纯错误弹窗，丢掉 `existingTask` | 409 响应体含可继续跟进的既有任务 |
| 建 `@/api` barrel 导入 | 一域一文件、具体文件导入 |
