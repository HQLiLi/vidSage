/**
 * 媒体源字节地址：给 `<video src>` 用的全路径。
 *
 * 它不走 axios（`<video>` 只认 src），吃不到 request.ts 里实例的 baseURL，
 * 所以按 api.md 的双拼法直接写 `/api/...` 全路径——这是刻意的，不是漏了前缀。
 * taskNo 编码一下：任务号里有 `/` 之类的字符时不会把请求带到别的路径上。
 */
export function mediaSourceUrl(taskNo: string): string {
  return `/api/media/source/${encodeURIComponent(taskNo)}`
}
