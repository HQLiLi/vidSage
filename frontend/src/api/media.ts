import { request } from './request'
import type { ExternalToolReport } from './types'

/**
 * 诊断外部工具（yt-dlp / ffmpeg / ffprobe）：返回可用性、解析到的绝对路径与版本，
 * 顺序固定；yt-dlp 版本过期时 hint 为新鲜度告警。只读、秒级。
 */
export async function getTools(): Promise<ExternalToolReport[]> {
  return (await request.get<ExternalToolReport[]>('/api/media/tools')).data
}
