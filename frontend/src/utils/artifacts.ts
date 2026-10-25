/**
 * 产物清单的挑选与取文件地址（纯函数，不发请求、不持状态）。
 */
import { ARTIFACT_TYPE } from '@/enums/subtitle'
import type { ArtifactResponse, ArtifactType } from '@/types/subtitle'

/**
 * 按类型挑一版产物；给了 lang 再按语种挑（大小写不敏感）。
 * 找不到返回 null。
 */
export function pickArtifact(
  artifacts: ArtifactResponse[],
  type: ArtifactType,
  lang?: string,
): ArtifactResponse | null {
  const byType = artifacts.filter((artifact) => artifact.type === type)
  if (lang === undefined) return byType[0] ?? null
  const target = lang.toLowerCase()
  return byType.find((artifact) => artifact.lang.toLowerCase() === target) ?? null
}

/**
 * 取产物里某个文件的下载地址。
 *
 * files 是 Record，索引访问可能是 undefined（项目开着 noUncheckedIndexedAccess），
 * 所以这里把「产物为空」「这个文件位不存在」都收成 null，由调用方决定怎么显示。
 */
export function artifactFileUrl(artifact: ArtifactResponse | null, key: string): string | null {
  return artifact?.files[key]?.url ?? null
}

/** 产物类型的展示名。 */
export const ARTIFACT_TYPE_LABEL: Record<ArtifactType, string> = {
  [ARTIFACT_TYPE.ORIGINAL]: '原始转写',
  [ARTIFACT_TYPE.CLEANED]: '清洗后字幕',
  [ARTIFACT_TYPE.TRANSLATED]: '译文',
}
