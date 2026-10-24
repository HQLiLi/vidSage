import { request } from './request'
import type { LanguageOption } from '@/types/subtitle'

/**
 * 读可选语种清单：源语种与目标语种的下拉都用它，不在前端另造一份语言名映射。
 */
export async function getLanguages(): Promise<LanguageOption[]> {
  return request.get<LanguageOption[]>('/languages')
}
