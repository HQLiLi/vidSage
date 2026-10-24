/**
 * 语种码归一（纯函数）。
 *
 * 后端 context.json 里的 sourceLanguage 是模型写的自由文本（可能是 zh、中文、zh-CN），
 * 不保证等于 /api/languages 给的码。前端要把这种自由文本落到下拉里能选的规范码上。
 */
import type { LanguageOption } from '@/types/subtitle'

/** 中文同义词 → 规范码。只在目标码真的存在于清单里时才采用。 */
const CHINESE_ALIASES: ReadonlyArray<readonly [string, string]> = [
  ['中文', 'zh-Hans'],
  ['汉语', 'zh-Hans'],
  ['简体', 'zh-Hans'],
  ['简体中文', 'zh-Hans'],
  ['繁体', 'zh-Hant'],
  ['繁体中文', 'zh-Hant'],
  ['英文', 'en'],
  ['英语', 'en'],
  ['日文', 'ja'],
  ['日语', 'ja'],
]

/**
 * 把自由文本语种码归一到清单里的规范码；归不拢时返回 null。
 *
 * 依次尝试：与 code 全等 → 与 label 全等 → 中文同义词 → 按前缀猜简繁。
 * 归不拢就返回 null，不猜一个默认值——下拉里挂一个不存在的选项比空着更糟。
 */
export function normalizeLanguageCode(raw: string, options: LanguageOption[]): string | null {
  const text = raw.trim()
  if (!text) return null

  const lower = text.toLowerCase()
  const byCode = options.find((option) => option.code.toLowerCase() === lower)
  if (byCode) return byCode.code

  const byLabel = options.find((option) => option.label === text)
  if (byLabel) return byLabel.code

  for (const [alias, code] of CHINESE_ALIASES) {
    if (text !== alias) continue
    const hit = options.find((option) => option.code === code)
    if (hit) return hit.code
  }

  // 前缀推断：zh 看有没有繁体的标记，其余按前缀认
  if (lower.startsWith('zh')) {
    const traditional = lower.includes('hant') || lower.includes('tw') || lower.includes('hk') || lower.includes('mo')
    const code = traditional ? 'zh-Hant' : 'zh-Hans'
    const hit = options.find((option) => option.code === code)
    if (hit) return hit.code
  }
  if (lower.startsWith('en')) {
    const hit = options.find((option) => option.code === 'en')
    if (hit) return hit.code
  }
  if (lower.startsWith('ja')) {
    const hit = options.find((option) => option.code === 'ja')
    if (hit) return hit.code
  }

  return null
}
