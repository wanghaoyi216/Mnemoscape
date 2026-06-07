/**
 * 安全 markdown 渲染 helper（v12：加 Mermaid 支持 + 强化语法兼容）。
 *
 * AI 流式回复每次 token append 都会重新渲染一次；marked 是同步、轻量的，
 * dompurify 把所有 `script` / `iframe` / `on*=` 清掉，避免恶意提示词注入
 * HTML/JS 混入聊天泡。
 *
 * 输出策略：
 *   - 标题 / 列表 / 引用 / 行内 code / 代码块 / 表格都允许；
 *   - URL 链接保留，加 target=_blank + rel="noopener noreferrer"；
 *   - 不允许 <img>（聊天泡内不渲染外链图片，attachments 用专用区域处理）；
 *   - ```mermaid ... ``` 代码块 → 输出为 <div class="mermaid">...</div>，
 *     由 ensureMermaidRendered() 在 DOM 挂载后异步调 mermaid.run() 渲染。
 *
 * Mermaid lazy-loaded：第一次有 mermaid 代码块时才动态 import，避免 70KB+
 * bundle 进入主包。
 */
import { marked, Renderer } from 'marked'
import DOMPurify from 'dompurify'

// 自定义 renderer：对 ```mermaid``` fenced code 输出 <div class="mermaid">
const renderer = new Renderer()
const baseCode = renderer.code.bind(renderer)
;(renderer.code as unknown) = (code: { text: string; lang?: string } | string, lang?: string): string => {
  // marked v12 把 (code, infostring, escaped) 改成对象参数；做兼容
  const codeText = typeof code === 'string' ? code : code.text
  const langTag = typeof code === 'string' ? lang : code.lang
  if (langTag === 'mermaid') {
    // 把原文内容嵌入 .mermaid div；mermaid.run() 会找到这个 class 并替换为 SVG
    // 用 textContent 思维：换行保留，但 HTML 特殊字符 escape
    const escaped = codeText
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
    return `<div class="mermaid" data-source="${encodeURIComponent(codeText)}">${escaped}</div>\n`
  }
  return baseCode(typeof code === 'string' ? { text: code, lang: lang || '' } as any : code)
}

marked.setOptions({
  breaks: true,        // 单换行 → <br>，符合直觉
  gfm: true,           // GitHub-flavored markdown
  renderer,
})

const ALLOWED_TAGS = [
  'p', 'br', 'strong', 'em', 'b', 'i', 'u', 's', 'del',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'ul', 'ol', 'li',
  'blockquote', 'hr',
  'code', 'pre',
  'a',
  'table', 'thead', 'tbody', 'tr', 'th', 'td',
  'span', 'div',
  // mermaid 渲染后会注入 <svg>
  'svg', 'g', 'defs', 'marker', 'path', 'polygon', 'polyline',
  'circle', 'ellipse', 'line', 'rect', 'text', 'tspan',
  'style', 'foreignObject',
]

const ALLOWED_ATTR = [
  'href', 'title', 'target', 'rel', 'class', 'lang', 'data-source',
  // SVG 属性（mermaid 输出会用到）
  'id', 'd', 'x', 'y', 'x1', 'y1', 'x2', 'y2', 'cx', 'cy', 'r', 'rx', 'ry',
  'width', 'height', 'viewBox', 'transform', 'fill', 'stroke', 'stroke-width',
  'stroke-dasharray', 'stroke-linecap', 'stroke-linejoin', 'opacity',
  'preserveAspectRatio', 'points', 'marker-end', 'marker-start',
  'text-anchor', 'dominant-baseline', 'font-family', 'font-size', 'font-weight',
  'xmlns', 'xmlns:xlink', 'xlink:href',
]

/** 渲染 markdown 到安全 HTML。失败时返回原始文本（被 escape）。 */
export function renderMarkdown(src: string | null | undefined): string {
  if (!src) return ''
  try {
    const raw = marked.parse(src, { async: false }) as string
    const clean = DOMPurify.sanitize(raw, {
      ALLOWED_TAGS,
      ALLOWED_ATTR,
      ADD_ATTR: ['target', 'data-source'],
      FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'form', 'input', 'img'],
      FORBID_ATTR: ['onclick', 'onload', 'onerror', 'onmouseenter', 'onmouseover'],
    })
    // 给所有外链 a 加 target/rel
    return clean.replace(
      /<a\s+([^>]*?)href=("[^"]+"|'[^']+')([^>]*?)>/gi,
      (_m, pre: string, href: string, post: string) =>
        `<a ${pre}href=${href}${post} target="_blank" rel="noopener noreferrer">`,
    )
  } catch {
    // 任何异常都不能炸 chat — 退回到 escape 原文
    return escapeHtml(src)
  }
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/* ================== Mermaid 集成 ================== */

let mermaidPromise: Promise<typeof import('mermaid')['default']> | null = null
async function loadMermaid() {
  if (!mermaidPromise) {
    mermaidPromise = import('mermaid').then((m) => {
      const md = m.default
      md.initialize({
        startOnLoad: false,
        theme: 'dark',
        securityLevel: 'strict',
        themeVariables: {
          background: '#0a0d12',
          primaryColor: '#36d8b4',
          primaryTextColor: '#f4f7fa',
          lineColor: '#5ee5d9',
          secondaryColor: '#fcd76a',
          tertiaryColor: '#5b8def',
        },
        fontFamily: '"Mnemoscape Mono", "Mnemoscape Hand", "Inter", monospace',
      })
      return md
    })
  }
  return mermaidPromise
}

/**
 * 在 markdown 渲染完成后调用 — 找到 root 内所有 .mermaid 块，
 * 逐个调 mermaid.run() 把源码替换成 SVG 图。
 *
 * 流式期间会被反复调用；用 data-mermaid-rendered 标记已处理过的，
 * 避免重复 init / 闪烁。
 */
export async function ensureMermaidRendered(root: HTMLElement | null) {
  if (!root) return
  const blocks = root.querySelectorAll<HTMLElement>('.mermaid:not([data-mermaid-rendered])')
  if (!blocks.length) return
  let mermaid
  try {
    mermaid = await loadMermaid()
  } catch (e) {
    console.warn('[useMarkdown] mermaid load failed:', e)
    return
  }
  for (const el of Array.from(blocks)) {
    try {
      const src = decodeURIComponent(el.dataset.source || el.textContent || '')
      el.textContent = src // 还原源码（DOMPurify escape 后会有 &lt;）
      el.dataset.mermaidRendered = '1'
      // mermaid.run 接受 nodes 数组
      await mermaid.run({ nodes: [el], suppressErrors: true })
    } catch (e) {
      console.warn('[useMarkdown] mermaid render failed:', e)
      el.dataset.mermaidRendered = '1' // 标记失败，避免无限重试
      el.textContent = '⚠️ mermaid 渲染失败'
    }
  }
}
