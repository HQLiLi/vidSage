/**
 * GSAP 全项目统一入口。
 *
 * 约束（详见 .trellis/spec/frontend/animation.md）：
 * - 这里是全项目唯一的 `gsap.registerPlugin()` 调用点。启用插件（ScrollTrigger、
 *   Flip 等）时只在此文件 `import + registerPlugin`；组件一律从 `@/lib/gsap`
 *   导入 gsap，禁止直接 `from 'gsap'`，保证插件注册对所有消费者自动生效。
 * - re-export 与 `gsap` 包是同一模块单例，不产生第二实例。
 * - 当前无插件使用场景，故暂无注册调用。
 */
export { gsap } from 'gsap'
