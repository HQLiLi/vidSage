import axios from 'axios'

/**
 * 全项目唯一的 axios 实例：与后端通信的非 SSE HTTP 请求都走它。
 *
 * - 同源相对路径：不设 baseURL，请求路径一律写 `/api/...`——dev 由 Vite
 *   `server.proxy` 转发到 localhost:8080，生产由部署层反代（零 CORS、零 env）。
 * - SSE（`GET /api/tasks/{taskNo}/events`）不走 axios（不支持流），
 *   接入时走 `composables/` + EventSource / fetch，约定见 api.md。
 * - 导入方式：`import { request } from '@/api/request'`；不要另建实例。
 */
export const request = axios.create({
  timeout: 15000,
})

/**
 * 响应错误拦截器骨架：错误原样透传给调用方，按 HTTP 状态码分支处理
 * （202 成功受理 / 400 参数缺失 / 409 冲突——冲突响应体为 TaskConflictResponse，
 * 含可直接跟进的既有任务视图）。如需全局 toast、埋点或 401 跳转，
 * 在此追加，不改动各调用方。
 */
request.interceptors.response.use(
  (response) => response,
  (error) => Promise.reject(error),
)
