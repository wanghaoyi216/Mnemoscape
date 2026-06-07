import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * Toast 语调，对应 UI 颜色 / 图标。
 * - info：中性提示
 * - success：成功反馈
 * - warning：警告（如 R4.3 admin guard 拦截）
 * - error：错误（如 R5.5 面板请求失败、R18.2 degraded badge）
 */
export type ToastTone = 'info' | 'success' | 'warning' | 'error'

/**
 * Toast 输入：调用方只需要给出 i18n key 与语调，
 * `id` 由 store 自动分配。
 */
export interface ToastInput {
  /** vue-i18n key，如 `admin.guard.notAdmin` */
  key: string
  /** 视觉语调 */
  tone: ToastTone
  /** 可选 i18n 参数，如 { n: 5 } */
  params?: Record<string, string | number>
  /** 自动关闭毫秒数；默认 4000；0 表示不自动关闭。 */
  durationMs?: number
}

/**
 * 已挂载的 toast：在 ToastInput 上追加 store 自动生成的 id。
 */
export interface Toast extends ToastInput {
  id: number
}

/**
 * 极轻量 toast store，复用于：
 * - R4.3：路由守卫拦截非 ADMIN 用户时的非阻塞通知
 * - R5.5：面板请求失败时的错误卡 / 全局通知
 * - R18.2：聚合接口 `degraded: true` 时的降级 badge
 *
 * 设计原则：仅暴露 `push` / `dismiss`，不内置定时关闭、不耦合任何 UI 组件——
 * UI 层（toast 容器组件）订阅 `toasts` 数组自行渲染与计时。
 */
export const useToastStore = defineStore('toast', () => {
  const toasts = ref<Toast[]>([])
  let nextId = 1

  /**
   * 推入一条 toast，返回新分配的 id 以便后续 `dismiss`。
   */
  function push(input: ToastInput): number {
    const id = nextId++
    toasts.value.push({
      id,
      key: input.key,
      tone: input.tone,
      params: input.params,
      durationMs: input.durationMs,
    })
    const ttl = input.durationMs === undefined ? 4000 : input.durationMs
    if (ttl > 0) {
      setTimeout(() => dismiss(id), ttl)
    }
    return id
  }

  /**
   * 按 id 移除一条 toast；若 id 不存在则静默忽略。
   */
  function dismiss(id: number): void {
    const idx = toasts.value.findIndex((t) => t.id === id)
    if (idx !== -1) {
      toasts.value.splice(idx, 1)
    }
  }

  return { toasts, push, dismiss }
})
