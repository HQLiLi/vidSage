import { request } from './request'
import type { ArtifactResponse } from '@/types/subtitle'

/**
 * 读一个任务的产物清单：原始转写 / 清洗后字幕 / 译文各一版，
 * 每版带版本号与其中的文件（下载地址、字节数）。
 * taskNo 不存在时后端返回 404。
 */
export async function listArtifacts(taskNo: string): Promise<ArtifactResponse[]> {
  return request.get<ArtifactResponse[]>(`/tasks/${taskNo}/artifacts`)
}

/**
 * 取一个产物文件的 JSON 内容。
 *
 * url 用清单给的那个全路径（如 /api/tasks/{taskNo}/artifacts/CLEANED/segments.json），
 * 不由前端自己拼——拼法由后端单点决定，前端再维护一套就会走偏。
 * 泛型 T 由调用方按产物类型标注。
 */
export async function fetchArtifactJson<T>(url: string): Promise<T> {
  return request.getAbsolute<T>(url)
}
