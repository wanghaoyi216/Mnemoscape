/// <reference types="vite/client" />

// Vue 单文件组件的类型声明 shim。
// 没有 shim 时 tsc --noEmit 会报 "Cannot find module '*.vue'"。
// Vue Language Server / vue-tsc 能识别 .vue，但纯 tsc 需要这层声明。
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}
