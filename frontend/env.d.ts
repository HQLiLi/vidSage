/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 后端 API 统一前缀（axios baseURL）；缺省回退 /api（同源部署形态，见 api.md）。 */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
