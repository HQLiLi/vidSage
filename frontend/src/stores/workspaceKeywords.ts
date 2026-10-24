import { ref } from 'vue'
import { defineStore } from 'pinia'

import { getTaskKeywords } from '@/api/tasks'

/** 加关键词的结果，页面据此给不同的提示。 */
export type KeywordAddResult = 'added' | 'empty' | 'too-long' | 'too-many' | 'duplicate'

/** 关键词上限：单条 50 字、最多 50 条（与后端一致）。关键词区组件也用这两个数算禁用态。 */
export const MAX_KEYWORD_LENGTH = 50
export const MAX_KEYWORDS = 50

/**
 * 工作区的关键词域：服务端读来的清单，加上用户在本页的增删。
 *
 * 增删只改这里的清单，不发请求——「重新生成」时才会把整份清单提交上去，
 * 所以上限校验放在这里，保住唯一的真相源。
 */
export const useWorkspaceKeywordsStore = defineStore('workspaceKeywords', () => {
  /** 关键词清单（服务端读来的，用户增删后也在这里）。 */
  const keywords = ref<string[]>([])
  /** 关键词指纹；本任务只存不用。 */
  const keywordsFingerprint = ref<string | null>(null)

  /** 加载的代际标记：切任务时靠它把上一次还没回来的请求作废。 */
  let loadToken = 0

  /**
   * 从服务端读关键词清单。
   *
   * 关键词是附属信息：拉不到就当作没设，不把整页拖进错误卡。
   */
  async function load(taskNo: string): Promise<void> {
    const token = (loadToken += 1)
    const result = await getTaskKeywords(taskNo).catch(() => null)
    if (token !== loadToken) return
    if (result === null) return
    keywords.value = result.keywords
    keywordsFingerprint.value = result.fingerprint
  }

  /**
   * 加一个关键词，返回结果供输入区给提示。
   */
  function addKeyword(text: string): KeywordAddResult {
    const value = text.trim()
    if (!value) return 'empty'
    if (Array.from(value).length > MAX_KEYWORD_LENGTH) return 'too-long'
    if (keywords.value.length >= MAX_KEYWORDS) return 'too-many'
    if (keywords.value.includes(value)) return 'duplicate'
    keywords.value = [...keywords.value, value]
    return 'added'
  }

  /** 删一个关键词（按值删第一条匹配的）。 */
  function removeKeyword(text: string): void {
    keywords.value = keywords.value.filter((keyword) => keyword !== text)
  }

  /** 所有状态回初值。换任务或离开页面时调用。 */
  function reset(): void {
    loadToken += 1
    keywords.value = []
    keywordsFingerprint.value = null
  }

  return {
    // state
    keywords,
    keywordsFingerprint,
    // actions
    load,
    addKeyword,
    removeKeyword,
    reset,
  }
})
