import axios, { AxiosError } from 'axios'
import type { AxiosRequestConfig } from 'axios'

import type { TaskRunRef } from '@/types/task'

/**
 * 全项目唯一的 axios 请求入口（非 SSE HTTP 都走它）：
 *
 * - baseURL 统一前缀来自 env（`VITE_API_BASE_URL`，见 frontend/.env；缺省回退 `/api`，
 *   保证无 env 文件的构建不坏）。request 衍生的调用点一律写相对路径（如 `/tasks`），
 *   不带 `/api` 前缀——dev 由 Vite `server.proxy` 把 /api 转发到 localhost:8080，
 *   生产由部署层反代（零 CORS）。
 * - 成功响应在拦截器里统一解包 `response.data`：调用点直接拿业务数据，**禁止再手写
 *   `.data`**——解包后门面返回的已是业务数据 T，手写 `.data` 只会得到 undefined。
 * - 失败响应统一经拦截器归一化为 ApiError（status / message / runRef），
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
 * runRef 分支。全局 toast、埋点或 401 跳转在本文件 instance 上追加，不改各调用方。
 */
instance.interceptors.response.use(
  (response) => response.data,
  (error: unknown) => Promise.reject(normalizeApiError(error)),
)

/**
 * 类型化门面：对外暴露的请求入口。调用点 `request.get<TaskResponse>(...)` 直接拿到
 * `Promise<TaskResponse>`，且不可能再写出「单泛型 + 运行时 `.data` 为 undefined」的错误
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
  /**
   * 请求一个已经带好前缀的绝对地址（产物清单给的下载链接就是这种，形如
   * /api/tasks/{taskNo}/artifacts/CLEANED/segments.json）。
   *
   * 做法是逐请求把 baseURL 置空，而不是把 /api 前缀从 url 里剥掉：仍是同一个实例，
   * 也不猜 baseURL 的字面前缀——env 里的前缀一旦改了，剥前缀就会悄悄拼错。
   */
  getAbsolute: <T>(url: string, config?: AxiosRequestConfig): Promise<T> =>
    instance.get<T, T>(url, { ...config, baseURL: '' }) as Promise<T>,
}

/**
 * 后端错误的统一形状（由响应拦截器归一化产出，调用点 catch 后只读这三个字段）。
 * message 恒为面向用户的中文文案。
 */
export class ApiError extends Error {
  /** HTTP 状态码；无响应的网络级错误（超时/断网）为 null。 */
  readonly status: number | null
  /**
   * 409 冲突时指向的那次执行（后端 409 与 202 同形，只回 {taskNo, runNo}，没有错误文案）。
   * 其余情况恒为 null。
   */
  readonly runRef: TaskRunRef | null

  /**
   * 三个字段全量显式传入（缺省即 null），保证实例一构造出来就是终态，
   * 不存在半构造的中间态可供调用点误读。
   */
  constructor(message: string, status: number | null = null, runRef: TaskRunRef | null = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.runRef = runRef
  }
}

/** 判定未知响应体是否为后端错误体 `{ "error": 中文说明 }`（ApiExceptionHandler 的统一映射）。 */
function isErrorBody(data: unknown): data is { error: string } {
  if (typeof data !== 'object' || data === null) return false
  return 'error' in data && typeof data.error === 'string'
}

/**
 * 判定未知响应体是否是 409 冲突体：后端冲突时回 {taskNo, runNo}，
 * 没有 error 字段，所以只能按这两个字段的形状认。
 */
function isRunRefBody(data: unknown): data is TaskRunRef {
  if (typeof data !== 'object' || data === null) return false
  if (!('taskNo' in data) || typeof data.taskNo !== 'string') return false
  return 'runNo' in data && typeof data.runNo === 'number'
}

/** 409 冲突的固定文案：后端冲突体里没有错误说明，这里补一句人话。 */
const CONFLICT_MESSAGE = '该任务已有一次执行正在进行'

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
    // 有响应体：读状态码与后端 error 文案；409 且形如 {taskNo, runNo} 时带上那个引用
    if (status !== null) {
      if (status === 409 && isRunRefBody(data)) {
        return new ApiError(CONFLICT_MESSAGE, status, { taskNo: data.taskNo, runNo: data.runNo })
      }
      return new ApiError(isErrorBody(data) ? data.error : `请求失败（HTTP ${status}）`, status)
    }
    // 无响应：ECONNABORTED 是 axios 对超时的标记，其余按断网处理
    if (error.code === 'ECONNABORTED') return new ApiError('请求超时，请稍后重试')
    return new ApiError('网络异常，请检查连接后重试')
  }
  return new ApiError('请求失败，请稍后重试')
}
