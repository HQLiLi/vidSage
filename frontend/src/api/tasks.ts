import { request } from './request'
import type { SubmitUrlRequest, TaskView } from './types'

/**
 * 提交 URL 任务（202 Accepted）：后端异步执行到原文字幕就绪，立即返回 PENDING 视图，
 * 翻译需任务成功后另调 translate 触发。同一 URL 已有排队 / 执行中任务时抛 409，
 * 错误响应体为 TaskConflictResponse（含既有任务视图）。
 */
export async function submitUrl(data: SubmitUrlRequest): Promise<TaskView> {
  return (await request.post<TaskView>('/api/tasks', data)).data
}

/**
 * 上传本地视频 / 音频文件提交任务（202 Accepted）：multipart 字段名固定为 `file`，
 * 后端流式落盘、按内容寻址（同一文件重复上传命中同一工作区，409 时返回既有任务）。
 */
export async function submitUpload(file: File): Promise<TaskView> {
  const form = new FormData()
  form.append('file', file)
  return (
    await request.post<TaskView>('/api/tasks/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  ).data
}

/**
 * 按 taskNo 查询任务视图：拿最新状态、阶段名与产物清单。
 * taskNo 不存在时后端返回 404。
 */
export async function getTask(taskNo: string): Promise<TaskView> {
  return (await request.get<TaskView>(`/api/tasks/${taskNo}`)).data
}

/**
 * 触发翻译（202 Accepted）：仅当任务 SUCCEEDED 后可调；对已完成翻译重复调用幂等收敛。
 */
export async function translate(taskNo: string): Promise<TaskView> {
  return (await request.post<TaskView>(`/api/tasks/${taskNo}/translate`)).data
}
