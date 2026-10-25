import { request } from './request'
import type {
  RegenerateSubtitlesRequest,
  SubmitTaskRequest,
  TaskResponse,
  TaskRunRef,
  TranslationRequest,
} from '@/types/task'
import type {
  ClearStaleCorrectionsResponse,
  CorrectionField,
  SaveCorrectionRequest,
  SubtitleCorrection,
  SubtitleCorrectionListResponse,
  SubtitleKeywordsResponse,
} from '@/types/subtitle'

/**
 * 提交链接任务（202 Accepted）：后端异步跑主管线，立即回本次执行的引用。
 * 该任务已有一次执行在跑时回 409，响应体同样是 {taskNo, runNo}——
 * 拿它继续跟进那条正在跑的 run，不当纯错误看。
 */
export async function submitUrl(data: SubmitTaskRequest): Promise<TaskRunRef> {
  return request.post<TaskRunRef>('/tasks', data)
}

/**
 * 上传本地视频 / 音频文件提交任务（202 Accepted）：multipart 字段名固定为 `file`，
 * 后端流式落盘、按内容寻址（同一文件重复上传命中同一工作区，409 时回既有执行引用）。
 *
 * - `keywords`：可选的初始关键词，**放进 FormData**（每个词一个同名字段）。
 *   不能走查询参数：axios 1.x 默认把数组序列化成 `keywords[]=a`，而后端
 *   `@RequestParam("keywords")` 按确切名字取参，`keywords[]` 绑不上，词会被静默丢掉；
 *   走 FormData 则由后端按同名多值字段收。不传这个字段就是「不设关键词」。
 * - `onUploadProgress`：可选上传进度回调。axios 进度事件每次触发时把 `loaded / total`
 *   换算成 0–100 整数百分比上抛，供上传页驱动进度条；total 未知（响应无
 *   Content-Length）时拿不到确定百分比，跳过该次回调。
 * - 不手动设置 `Content-Type`：data 为 FormData 时须由浏览器自动生成带 boundary
 *   的请求头，显式覆写会丢失 boundary 而被后端拒绝。
 * - 本请求覆写 `timeout: 0`（禁用超时）：实例全局 15s 超时对上限 2GB 的大文件上传
 *   必然误伤，仅此请求放开。
 */
export async function submitUpload(
  file: File,
  keywords?: string[],
  onUploadProgress?: (percent: number) => void,
): Promise<TaskRunRef> {
  const form = new FormData()
  form.append('file', file)
  for (const keyword of keywords ?? []) {
    form.append('keywords', keyword)
  }
  return request.post<TaskRunRef>('/tasks/upload', form, {
    timeout: 0,
    onUploadProgress: (event) => {
      if (!onUploadProgress || !event.total) return
      // 钳制到 0–100：浮点换算的防御性收边
      onUploadProgress(Math.min(100, Math.max(0, Math.round((event.loaded / event.total) * 100))))
    },
  })
}

/**
 * 按 taskNo 查询任务快照：拿任务来源与最近一次执行的状态。
 * taskNo 不存在时后端返回 404。
 */
export async function getTask(taskNo: string): Promise<TaskResponse> {
  return request.get<TaskResponse>(`/tasks/${taskNo}`)
}

/**
 * 读任务的关键词清单与其指纹。清单按用户填写顺序返回。
 * 从未设过关键词时是空数组（不是 null）。
 */
export async function getTaskKeywords(taskNo: string): Promise<SubtitleKeywordsResponse> {
  return request.get<SubtitleKeywordsResponse>(`/tasks/${taskNo}/keywords`)
}

/**
 * 触发翻译（202 Accepted）：在已有的清洗后字幕上产出译文。
 * 该任务已有一次执行在跑时回 409，响应体是 {taskNo, runNo}。
 */
export async function translateTask(taskNo: string, data: TranslationRequest): Promise<TaskRunRef> {
  return request.post<TaskRunRef>(`/tasks/${taskNo}/translate`, data)
}

/**
 * 触发重新生成字幕（202 Accepted）：按请求里的关键词重跑清洗。
 * keywords 是数组：空数组表示清空关键词后重跑；字段缺席（null）表示沿用现有清单。
 * 该任务已有一次执行在跑时回 409，响应体是 {taskNo, runNo}。
 */
export async function regenerateSubtitles(
  taskNo: string,
  data: RegenerateSubtitlesRequest,
): Promise<TaskRunRef> {
  return request.post<TaskRunRef>(`/tasks/${taskNo}/subtitles/regenerate`, data)
}

/**
 * 读该任务的全部字幕纠错，每条带 matched（当前字幕里还有没有这一条）。
 * 纠错是附属信息：拉不到不影响字幕本身，只影响失效提示与恢复入口。
 */
export async function listCorrections(taskNo: string): Promise<SubtitleCorrectionListResponse> {
  return request.get<SubtitleCorrectionListResponse>(`/tasks/${taskNo}/corrections`)
}

/**
 * 保存一条纠错：同一段同一项重复保存就是覆盖，不追加历史。
 * 任务在跑（409）、条目已过期（409）、该语种没有可用译文（400）都由后端给中文文案。
 */
export async function saveCorrection(
  taskNo: string,
  segmentId: string,
  data: SaveCorrectionRequest,
): Promise<SubtitleCorrection> {
  return request.put<SubtitleCorrection>(
    `/tasks/${taskNo}/corrections/${encodeURIComponent(segmentId)}`,
    data,
  )
}

/**
 * 删除一条纠错：删除即恢复机器文本，条目不存在的也是 204。
 * field=text 时 lang 传空串即可，后端不读它。
 */
export async function deleteCorrection(
  taskNo: string,
  segmentId: string,
  field: CorrectionField,
  lang: string,
): Promise<void> {
  return request.delete<void>(`/tasks/${taskNo}/corrections/${encodeURIComponent(segmentId)}`, {
    params: { field, lang },
  })
}

/**
 * 清理失效纠错：只删当前字幕里已经找不到的条目，返回实际删掉的条数。
 * 必须显式带 staleOnly=true——后端不支持「清空全部」。
 */
export async function clearStaleCorrections(
  taskNo: string,
): Promise<ClearStaleCorrectionsResponse> {
  return request.delete<ClearStaleCorrectionsResponse>(`/tasks/${taskNo}/corrections`, {
    params: { staleOnly: true },
  })
}
