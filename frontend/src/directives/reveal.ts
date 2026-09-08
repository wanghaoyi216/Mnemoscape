/**
 * v-reveal — 滚动进入视口时淡入上移的全局指令。
 *
 * 现有的 CSS `.reveal` 工具类只在元素 mount 时播放一次动画，首屏以下的内容
 * 在用户滚动到之前就已经"演完"，看不到入场效果。本指令改用 IntersectionObserver：
 * 元素真正进入视口（默认露出 12%）才触发淡入，离场不回退（一次性），更接近
 * 高端落地页的滚动叙事节奏。
 *
 * 用法：
 *   <section v-reveal>...</section>              // 默认
 *   <div v-reveal="{ delay: 120 }">...</div>     // 延迟 120ms
 *   <li v-reveal="{ y: 24, delay: i * 60 }">     // 自定义位移 + 级联
 *
 * 可访问性：prefers-reduced-motion 时直接显示，不做任何位移/过渡。
 */
import type { Directive, DirectiveBinding } from 'vue'

interface RevealOptions {
  /** 入场前的纵向位移（px），默认 18 */
  y?: number
  /** 延迟毫秒，默认 0；用于列表级联 */
  delay?: number
  /** 触发阈值 0~1，默认 0.12 */
  threshold?: number
}

const prefersReduced = () =>
  typeof window !== 'undefined' &&
  window.matchMedia &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches

const observers = new WeakMap<HTMLElement, IntersectionObserver>()

function applyHidden(el: HTMLElement, opts: RevealOptions) {
  const y = opts.y ?? 18
  el.style.opacity = '0'
  el.style.transform = `translateY(${y}px)`
  el.style.transition =
    'opacity 620ms cubic-bezier(0.165,0.84,0.44,1), transform 620ms cubic-bezier(0.165,0.84,0.44,1)'
  el.style.transitionDelay = `${opts.delay ?? 0}ms`
  el.style.willChange = 'opacity, transform'
}

function reveal(el: HTMLElement) {
  el.style.opacity = '1'
  el.style.transform = 'translateY(0)'
  // 动画结束后撤掉 will-change，避免长期占用合成层
  window.setTimeout(() => {
    el.style.willChange = 'auto'
  }, 900)
}

export const vReveal: Directive<HTMLElement, RevealOptions | undefined> = {
  mounted(el: HTMLElement, binding: DirectiveBinding<RevealOptions | undefined>) {
    const opts = binding.value ?? {}
    if (prefersReduced()) return // 直接保持可见

    applyHidden(el, opts)

    const observer = new IntersectionObserver(
      (entries, obs) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            reveal(el)
            obs.unobserve(el) // 一次性：进入即解除观察
          }
        }
      },
      { threshold: opts.threshold ?? 0.12, rootMargin: '0px 0px -8% 0px' },
    )
    observer.observe(el)
    observers.set(el, observer)
  },
  unmounted(el: HTMLElement) {
    const observer = observers.get(el)
    if (observer) {
      observer.disconnect()
      observers.delete(el)
    }
  },
}
