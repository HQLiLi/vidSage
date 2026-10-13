import axios, { AxiosError } from 'axios'
import type { AxiosRequestConfig } from 'axios'

import type { TaskConflictResponse, TaskView } from '@/types/task'

/**
 * 全项目唯一的 axios 请求入口（非 SSE HTTP 都走它）：
 *
 * - baseURL 统一前缀来自 env（`VITE_API_BASE_URL`，见 frontend/.env；缺省回退 `/api`，
 *   保证无 env 文件的构建不坏）。request 衍生的调用点一律写相对路径（如 `/tasks`），
 *   不带 `/api` 前缀——dev 由 Vite `server.proxy` 把 /api 转发到 localhost:8080，
 *   生产由部署层反代（零 CORS）。
 * - 成功响应在拦截器里统一解包 `response.data`：调用点直接拿业务数据，**禁止再手写
 *   `.data`**——解包后门面返回的已是业务数据 T，手写 `.data` 只会得到 undefined。
 * - 失败响应统一经拦截器归一化为 ApiError（status / message / existingTask），
 *   调用点 catch 后按字段分支，不再自行拆 axios 错误对象。
 * - SSE（EventSource）与 `<a download>` 产物链接不走 axios、吃不到 baseURL，
 *   仍写全路径 `/api/...`——这是刻意保留的双拼法而非遗漏（约定见 api.md）。
 * - instance 不导出（保持单入口）：拦截器等扩展点在本文件 instance 上追加，不要另建实例。
 */
const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 15000,
})

/**
 * 响应拦截器：成功路径统一解包 data（返回类型由下方类型化门面声明为已解包的 T）；
 * 失败路径把未知错误归一化为 ApiError 后再 reject，调用点按 status / message /
 * existingTask 分支。全局 toast、埋点或 401 跳转在本文件 instance 上追加，不改各调用方。
 */
instance.interceptors.response.use(
  (response) => response.data,
  (error: unknown) => Promise.reject(normalizeApiError(error)),
)

/**
 * 类型化门面：对外暴露的请求入口。调用点 `request.get<TaskView>(...)` 直接拿到
 * `Promise<TaskView>`，且不可能再写出「单泛型 + 运行时 `.data` 为 undefined」的错误
 * 用法。需要 put / delete 等其余方法时按同款签名在此追加。
 *
 * 末尾的 `as Promise<T>` 是全仓唯一的受控断言：axios ^1.19 的方法返回值是条件类型
 * `AxiosResponseResult`，第二个泛型位传泛型 T 时条件悬置不解析，与调用点的 `Promise<T>`
 * 标注冲突，故在门面这一处把返回类型掰直。**断言只许出现在本文件门面边界，调用点仍零 as**
 * （推导见 type-safety.md 的「唯一例外」）。
 */
export const request = {
  get: <T>(url: string, config?: AxiosRequestConfig): Promise<T> =>
    instance.get<T, T>(url, config) as Promise<T>,
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> =>
    instance.post<T, T>(url, data, config) as Promise<T>,
}

/**
 * 后端错误的统一形状（由响应拦截器归一化产出，调用点 catch 后只读这三个字段）。
 * message 恒为面向用户的中文文案：后端 error 字段、网络级文案或兜底文案三选一。
 */
export class ApiError extends Error {
  /** HTTP 状态码；无响应的网络级错误（超时/断网）为 null。 */
  readonly status: number | null
  /** 409 冲突时的既有任务视图（TaskConflictResponse）；其余恒为 null。 */
  readonly existingTask: TaskView | null

  /**
   * 三个字段全量显式传入（缺省即 null），保证实例一构造出来就是终态，
   * 不存在半构造的中间态可供调用点误读。
   */
  constructor(message: string, status: number | null = null, existingTask: TaskView | null = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.existingTask = existingTask
  }
}

/** 判定未知响应体是否为后端错误体 `{ "error": 中文说明 }`（ApiExceptionHandler 的统一映射）。 */
function isErrorBody(data: unknown): data is { error: string } {
  if (typeof data !== 'object' || data === null) return false
  return 'error' in data && typeof data.error === 'string'
}

/** 判定未知响应体是否为 409 冲突体 TaskConflictResponse（error + 含 taskNo 的既有任务视图）。 */
function isTaskConflictResponse(data: unknown): data is TaskConflictResponse {
  if (typeof data !== 'object' || data === null) return false
  if (!('error' in data) || typeof data.error !== 'string') return false
  if (
    !('existingTask' in data) ||
    typeof data.existingTask !== 'object' ||
    data.existingTask === null
  ) {
    return false
  }
  return 'taskNo' in data.existingTask
}

/**
 * 把未知错误归一化为 ApiError：axios 错误读响应体/状态码，超时与断网给对应文案，
 * 其余兜底通用文案；传入的已是 ApiError 时原样返回（幂等）。
 * 全程类型守卫收窄，不用 as 断言（type-safety.md）。
 */
function normalizeApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error
  if (error instanceof AxiosError) {
    const status = error.response?.status ?? null
    const data: unknown = error.response?.data
    // 有响应体：读状态码与后端 error 文案；409 且形如 TaskConflictResponse 时附带既有任务
    if (status !== null) {
      if (status === 409 && isTaskConflictResponse(data)) {
        return new ApiError(data.error, status, data.existingTask)
      }
      return new ApiError(isErrorBody(data) ? data.error : `请求失败（HTTP ${status}）`, status)
    }
    // 无响应：ECONNABORTED 是 axios 对超时的标记，其余按断网处理
    if (error.code === 'ECONNABORTED') return new ApiError('请求超时，请稍后重试')
    return new ApiError('网络异常，请检查连接后重试')
  }
  return new ApiError('请求失败，请稍后重试')
}
