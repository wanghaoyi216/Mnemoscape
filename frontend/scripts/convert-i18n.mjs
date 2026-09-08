// One-shot migration: JSON -> TS, with R31 new keys (ErrorBoundary, Skeleton, PWA).
// Run with: node scripts/convert-i18n.mjs
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const localeDir = path.join(__dirname, '..', 'src', 'i18n', 'locales')

const zh = JSON.parse(fs.readFileSync(path.join(localeDir, 'zh-CN.json'), 'utf8'))
const en = JSON.parse(fs.readFileSync(path.join(localeDir, 'en-US.json'), 'utf8'))

// Backfill: any key present in zh-CN but missing in en-US gets the zh-CN
// string as a placeholder. vue-i18n's fallbackLocale chain is "zh-CN ->
// en-US", so when the user picks en-US and we haven't translated a key yet,
// we DO NOT want the literal key (e.g. "memory.timeline.title") rendered.
// We want the zh-CN string backfilled so the UI is at least readable. The
// dev can later swap that string with a real translation.
function backfill(zhObj, enObj) {
  if (typeof zhObj !== 'object' || zhObj === null) return
  for (const k of Object.keys(zhObj)) {
    const v = zhObj[k]
    if (typeof v === 'object' && v !== null && !Array.isArray(v)) {
      if (!enObj[k] || typeof enObj[k] !== 'object') enObj[k] = {}
      backfill(v, enObj[k])
    } else {
      if (enObj[k] === undefined || enObj[k] === null) enObj[k] = v
    }
  }
}
backfill(zh, en)

function toTs(obj, indent = 2) {
  const pad = ' '.repeat(indent)
  const closingPad = ' '.repeat(Math.max(0, indent - 2))
  if (typeof obj === 'string') {
    return JSON.stringify(obj)
  }
  if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
    const lines = ['{']
    const entries = Object.entries(obj)
    entries.forEach(([k, v], idx) => {
      const comma = idx < entries.length - 1 ? ',' : ''
      let child
      if (typeof v === 'object' && v !== null && !Array.isArray(v)) {
        const nested = toTs(v, indent + 2)
        child = '\n' + nested.split('\n').map(l => pad + l).join('\n')
      } else {
        child = ' ' + toTs(v, indent + 2)
      }
      lines.push(`${pad}${JSON.stringify(k)}:${child}${comma}`)
    })
    lines.push(`${closingPad}}`)
    return lines.join('\n')
  }
  return JSON.stringify(obj)
}

// R31 additions — injected into both locales.
const r31Zh = {
  skeleton: {
    box: '加载中…',
    memoryCard: {
      title: '记忆标题占位',
      meta: '时间 · 地点',
      excerpt: '记忆摘要占位 — 这是一段 AI 重建的描述，等待加载完成。',
      tag: '标签'
    },
    chapterList: {
      heading: '章节标题',
      item: '章节列表项',
      timestamp: '时间戳'
    },
    chatMessage: {
      user: '正在输入消息…',
      ai: '星空使者正在思考…',
      timestamp: '刚刚'
    }
  },
  errorBoundary: {
    title: '页面出了点小问题',
    subtitle: '记忆的展厅暂时打不开 — 它已被隔离，避免影响其他展馆。',
    detail: '错误细节',
    stackLabel: '调用栈',
    componentLabel: '出错组件',
    retry: '重新加载这块记忆',
    goHome: '回到记忆博物馆首页',
    reportHint: '如果问题反复出现，可以截图后联系客服',
    copySuccess: '已复制到剪贴板',
    copyFailed: '复制失败，请手动选择文本'
  },
  pwa: {
    offline: {
      title: '已离线',
      body: '记忆博物馆目前无法连接 — 你仍可继续浏览已访问过的记忆。',
      retry: '重新尝试',
      queued: '排队中',
      synced: '已同步',
      queueHint: '{count} 个 AI 请求已离线排队，将在恢复后自动发送'
    },
    update: {
      available: '新版本已就绪',
      body: '已下载 R{version} 的更新，刷新即可使用。',
      refresh: '立即刷新',
      later: '稍后再说'
    },
    install: {
      title: '把记忆博物馆装到桌面',
      body: '安装后可离线浏览、收信标提醒，体验更沉浸。',
      install: '安装',
      dismiss: '暂不安装'
    }
  }
}

const r31En = {
  skeleton: {
    box: 'Loading…',
    memoryCard: {
      title: 'Memory title placeholder',
      meta: 'time · location',
      excerpt: 'Memory excerpt placeholder — this is an AI-reconstructed description waiting to load.',
      tag: 'tag'
    },
    chapterList: {
      heading: 'Chapter heading',
      item: 'Chapter list item',
      timestamp: 'Timestamp'
    },
    chatMessage: {
      user: 'Typing a message…',
      ai: 'Echo is thinking…',
      timestamp: 'Just now'
    }
  },
  errorBoundary: {
    title: 'Something went sideways',
    subtitle: 'A memory hall could not be opened — it has been isolated so the rest of the museum keeps running.',
    detail: 'Error details',
    stackLabel: 'Stack',
    componentLabel: 'Component',
    retry: 'Reload this memory',
    goHome: 'Back to the museum entrance',
    reportHint: 'If this keeps happening, screenshot it and contact support.',
    copySuccess: 'Copied to clipboard',
    copyFailed: 'Copy failed, please select the text manually'
  },
  pwa: {
    offline: {
      title: 'You are offline',
      body: 'The memory museum is unreachable — previously visited memories are still browsable.',
      retry: 'Try again',
      queued: 'Queued',
      synced: 'Synced',
      queueHint: '{count} AI requests are queued offline and will be sent automatically once reconnected.'
    },
    update: {
      available: 'A new version is ready',
      body: 'R{version} has been downloaded. Refresh to switch over.',
      refresh: 'Refresh now',
      later: 'Later'
    },
    install: {
      title: 'Install the memory museum',
      body: 'Install for offline browsing, beacon notifications and a more immersive feel.',
      install: 'Install',
      dismiss: 'Not now'
    }
  }
}

function deepInject(target, source) {
  for (const k of Object.keys(source)) {
    if (source[k] && typeof source[k] === 'object' && !Array.isArray(source[k])) {
      if (!target[k] || typeof target[k] !== 'object') target[k] = {}
      deepInject(target[k], source[k])
    } else {
      target[k] = source[k]
    }
  }
}

deepInject(zh, r31Zh)
deepInject(en, r31En)

const header = (name) => `/**
 * R31: ${name} i18n messages.
 * Auto-converted from legacy JSON, plus R31 additions:
 *  - skeleton.*          (SkeletonBox / SkeletonCard placeholders)
 *  - errorBoundary.*     (global ErrorBoundary fallback page)
 *  - pwa.*               (offline banner, update toast, install prompt)
 *
 * To find missing keys in <template>/<script>, run: pnpm i18n:extract
 */
`

fs.writeFileSync(
  path.join(localeDir, 'zh-CN.ts'),
  `${header('zh-CN')}\nexport default ${toTs(zh)} as const\n`
)
fs.writeFileSync(
  path.join(localeDir, 'en-US.ts'),
  `${header('en-US')}\nexport default ${toTs(en)} as const\n`
)

const zhKeyCount = JSON.stringify(zh).match(/"[^"]+":/g).length
const enKeyCount = JSON.stringify(en).match(/"[^"]+":/g).length
console.log(`[i18n-convert] zh-CN keys: ${zhKeyCount}`)
console.log(`[i18n-convert] en-US keys: ${enKeyCount}`)
console.log('[i18n-convert] wrote src/i18n/locales/zh-CN.ts and en-US.ts')
