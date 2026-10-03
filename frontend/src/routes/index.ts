import { createRouter, createWebHistory } from 'vue-router'

/**
 * 应用路由。当前为单文件，超过约 5 条路由再按域拆分到 routes/ 下的独立文件。
 * 页面组件一律动态导入：每个 views/ 子包独立成 chunk，访问到才加载。
 */
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'upload',
      component: () => import('@/views/upload/index.vue'),
    },
    {
      path: '/workspace/:videoId',
      name: 'workspace',
      component: () => import('@/views/workspace/index.vue'),
      // 页面通过 defineProps 接收路由参数，不在组件内用 useRoute() 取参。
      props: true,
    },
    {
      // 404 兜底：所有未匹配路径指向 not-found 页。
      path: '/:pathMatch(.*)*',
      name: 'notFound',
      component: () => import('@/views/not-found/index.vue'),
    },
  ],
})

export default router
