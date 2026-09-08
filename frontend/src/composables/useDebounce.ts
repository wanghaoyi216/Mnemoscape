import { ref, type Ref } from 'vue'

/**
 * 防抖 + 节流组合式函数。
 *
 * <p><b>设计目的</b>：高并发场景下，用户快速连点提交按钮会瞬间发出多个请求，
 * 即便后端有幂等守卫，前端也应主动拦截，减少无效流量并提升用户感知。
 *
 * <p><b>useDebouncedAction</b>：包装一个异步 action，使其在飞行中不可再触发，
 * 完成（成功/失败）后才接受下一次调用 —— 典型用途是"提交按钮"防双击。
 *
 * <p><b>useThrottledAction</b>：固定窗口内只允许触发一次，多用于"刷新"场景。
 */
export function useDebouncedAction<T extends (...args: any[]) => Promise<any>>(action: T) {
  const pending: Ref<boolean> = ref(false)

  async function run(...args: Parameters<T>): Promise<Awaited<ReturnType<T>> | undefined> {
    if (pending.value) {
      // 已经在飞行中，直接返回，避免重复触发
      return undefined
    }
    pending.value = true
    try {
      return await action(...args)
    } finally {
      pending.value = false
    }
  }

  return {
    pending,
    run,
  }
}

/**
 * 节流：在 windowMs 时间窗内只执行第一次调用，后续调用被丢弃。
 * 适合"刷新列表""重新生成场景"等可能被用户连点的操作。
 */
export function useThrottledAction<T extends (...args: any[]) => any>(action: T, windowMs = 2000) {
  const lastRun: Ref<number> = ref(0)

  function run(...args: Parameters<T>): ReturnType<T> | undefined {
    const now = Date.now()
    if (now - lastRun.value < windowMs) {
      return undefined
    }
    lastRun.value = now
    return action(...args)
  }

  return {
    lastRun,
    run,
  }
}
