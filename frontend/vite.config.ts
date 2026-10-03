import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue(), vueDevTools(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 同源策略：前端请求一律写相对路径 /api/...，dev 在此转发到后端，
    // 生产由部署层反代（约定见 .trellis/spec/frontend/api.md）
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
