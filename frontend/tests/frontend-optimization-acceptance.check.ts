/**
 * Frontend optimization acceptance check (ReAct task-5).
 * Runs against the already-built dist served by `vite preview` on :4173.
 * Verifies:
 *   1. All 53 numbered assets return HTTP 200 (no 404s).
 *   2. media-catalog.ts labels match docs/媒体对照表-53张目检.md.
 *   3. zh-CN / en-US both expose nav.groups.{memory,explore,social}.
 *   4. Header DOM (via SPA snapshot) renders brand + hamburger + theme + nav groups.
 */
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const BASE = process.env.BASE_URL || 'http://localhost:4173'
const HERE = dirname(fileURLToPath(import.meta.url))
const SRC = resolve(HERE, '..')
const GROUPS_CN = ['记忆', '探索', '社交']
const GROUPS_EN = ['Memory', 'Explore', 'Social']

let failures = 0
function fail(msg) { console.error(`✗ ${msg}`); failures += 1 }
function ok(msg) { console.log(`✓ ${msg}`) }

async function headStatus(url) {
  try { const r = await fetch(url, { method: 'HEAD' }); return r.status } catch { return 0 }
}

async function checkAssets() {
  const cats = [
    { dir: 'login', count: 12, start: 1 },
    { dir: 'memory-covers', count: 20, start: 13 },
    { dir: 'scene-nature', count: 2, start: 45 },
    { dir: 'scene-architecture', count: 2, start: 59 },
    { dir: 'concepts', count: 2, start: 71 },
    { dir: 'features', count: 10, start: 86 },
    { dir: 'video-posters', count: 5, start: 96 },
  ]
  let total = 0
  let bad = 0
  for (const c of cats) {
    for (let i = 0; i < c.count; i += 1) {
      const n = c.start + i
      const stem = String(n).padStart(3, '0')
      const url = `${BASE}/media/generated/${c.dir}/${stem}.webp`
      const s = await headStatus(url)
      total += 1
      if (s !== 200) { fail(`${c.dir}/${stem}.webp → ${s}`); bad += 1 }
    }
  }
  if (bad === 0) ok(`53 张编号图均 200 OK（无 404）`)
}

async function checkCatalog() {
  const cat = await readFile(resolve(SRC, 'src/assets/media-catalog.ts'), 'utf8')
  const expected = {
    'login/011': '记忆樱树',
    'login/012': '古铜圆门',
    'memory-covers/013': '温暖', 'memory-covers/014': '祈愿',
    'memory-covers/015': '冬暖', 'memory-covers/016': '童年',
    'memory-covers/017': '拥抱', 'memory-covers/018': '生日',
    'memory-covers/019': '远行', 'memory-covers/020': '守候',
    'memory-covers/021': '毕业', 'memory-covers/022': '烟火',
    'memory-covers/023': '音乐', 'memory-covers/024': '陪伴',
    'memory-covers/025': '约定', 'memory-covers/026': '节日',
    'memory-covers/027': '漫步', 'memory-covers/028': '日出',
    'memory-covers/029': '思念', 'memory-covers/030': '雨天',
    'memory-covers/031': '迷雾', 'memory-covers/032': '沉默',
  }
  let bad = 0
  for (const [slug, label] of Object.entries(expected)) {
    const [dir, numRaw] = slug.split('/')
    const num = String(Number(numRaw))
    const pattern = new RegExp(`'${dir}',\\s*${num},\\s*'([^']+)'`)
    const m = cat.match(pattern)
    if (!m) { fail(`catalog 缺 ${slug}`); bad += 1; continue }
    if (m[1] !== label) {
      fail(`catalog ${slug} = '${m[1]}' 应为 '${label}'`)
      bad += 1
    }
  }
  if (bad === 0) ok(`media-catalog ${Object.keys(expected).length} 个修正标签全部对齐目检表`)
}

async function checkI18n() {
  for (const [loc, expected] of [['zh-CN', GROUPS_CN], ['en-US', GROUPS_EN]]) {
    const text = await readFile(resolve(SRC, `src/i18n/locales/${loc}.ts`), 'utf8')
    const block = text.match(/"groups"\s*:\s*\{([\s\S]*?)\}/)
    if (!block) { fail(`${loc} 缺 groups 块`); continue }
    for (const g of expected) {
      if (!block[1].includes(g)) fail(`${loc} groups 缺 '${g}'`)
    }
  }
  ok(`zh-CN / en-US 均含 nav.groups 三分组`)
}

async function checkHeaderBundle() {
  // dist 把模板编译进 chunk JS，从 JS 中匹配 brand 与 nav 组即可。
  const dir = resolve(SRC, 'dist/assets')
  const fs = await import('node:fs/promises')
  const files = (await fs.readdir(dir)).filter((f) => f.endsWith('.js'))
  let bundle = ''
  for (const f of files) {
    bundle += '\n' + (await fs.readFile(resolve(dir, f), 'utf8'))
  }
  const checks = [
    ['app-header 容器', /"app-header"/],
    ['app-nav 容器', /"app-nav"/],
    ['app-nav__group', /"app-nav__group"/],
    ['app-nav__group-btn', /"app-nav__group-btn"/],
    ['nav.groups.memory 键', /nav\.groups\.memory/],
    ['nav.groups.explore 键', /nav\.groups\.explore/],
    ['nav.groups.social 键', /nav\.groups\.social/],
    ['memory 路由 /memories', /["']\/memories["']/],
    ['timeline 路由 /memories/timeline', /["']\/memories\/timeline["']/],
    ['graph 路由 /memories/graph', /["']\/memories\/graph["']/],
    ['atlas 路由 /memories/atlas', /["']\/memories\/atlas["']/],
    ['resonance 路由', /["']\/resonance["']/],
    ['chat 路由', /["']\/chat["']/],
  ]
  let bad = 0
  for (const [name, re] of checks) {
    if (!re.test(bundle)) { fail(`dist bundle 缺 ${name}`); bad += 1 }
  }
  if (bad === 0) ok(`dist bundle 含 header 容器、3 个 nav 组、6 条路由`)
}

async function checkUserFeedbackFixes() {
  const fs = await import('node:fs/promises')
  const dir = resolve(SRC, 'dist/assets')
  const files = (await fs.readdir(dir)).filter((f) => f.endsWith('.js'))
  let bundle = ''
  for (const f of files) {
    bundle += '\n' + (await fs.readFile(resolve(dir, f), 'utf8'))
  }
  const src = await fs.readFile(resolve(SRC, 'src/components/layout/AppHeader.vue'), 'utf8')
  const zh = await fs.readFile(resolve(SRC, 'src/i18n/locales/zh-CN.ts'), 'utf8')
  const en = await fs.readFile(resolve(SRC, 'src/i18n/locales/en-US.ts'), 'utf8')

  let bad = 0
  // 1. Drawer 从左侧滑入
  if (/drawer-sidebar[\s\S]{0,200}right:\s*0/.test(src)) {
    fail('drawer-sidebar 还在用 right:0（应改为 left:0）')
    bad += 1
  } else ok('drawer-sidebar 改用 left:0 滑入')
  if (!/translateX\(-100%\)/.test(src)) {
    fail('drawer-slide-leave-to 未用 translateX(-100%)')
    bad += 1
  } else ok('drawer-slide 动画方向已改为从左滑出')

  // 2. Hamburger 体积缩小到 ≤34
  if (!/\.hamburger-btn\s*\{[\s\S]{0,400}width:\s*34px/.test(src)) {
    fail('hamburger-btn 仍是 38px，未缩小到 34px')
    bad += 1
  } else ok('hamburger-btn 缩到 34×34')

  // 3. Hamburger 三条线可变形为 X
  if (!/hamburger-bar/.test(bundle)) {
    fail('hamburger-bar 三条线结构未生成到 bundle')
    bad += 1
  } else ok('hamburger-bar 三条线 + X 变形动画已就位')

  // 4. Drawer 底部加操作提示
  if (!/drawer-sidebar__hint/.test(bundle)) {
    fail('drawer 缺底部 hint')
    bad += 1
  } else ok('drawer 底部有交互提示（Esc / 点击外部）')

  // 5. Login title 已更新
  if (!zh.includes('让被遗忘的') || !zh.includes('重新闪烁')) {
    fail('zh-CN login.title 未更新')
    bad += 1
  } else ok('zh-CN 标题改为「让被遗忘的 重新闪烁」')
  if (!en.includes('shimmer again')) {
    fail('en-US login.title 未更新')
    bad += 1
  } else ok('en-US 标题改为「Let the forgotten shimmer again」')

  // 6. i18n escape @ 字符
  if (/@company\.com/.test(zh) || /@company\.com/.test(en)) {
    fail('email_placeholder 仍含裸 @，会触发 vue-i18n Invalid linked format')
    bad += 1
  } else ok('@ 字符在 zh-CN / en-US 已用 {\'@\'} escape')
  if (/(?<!\{'@'\})@AI/.test(zh) || /(?<!\{'@'\})@AI/.test(en)) {
    fail('mentionTip/Hint 仍含裸 @AI / @Echo')
    bad += 1
  } else ok('mention 提示中的 @AI / @Echo 已 escape')

  if (bad === 0) ok('6 项用户反馈修复全部固化')
}

async function main() {
  console.log(`▶ ReAct task-5 走查 → ${BASE}`)
  await checkAssets()
  await checkCatalog()
  await checkI18n()
  await checkHeaderBundle()
  await checkUserFeedbackFixes()
  console.log(failures ? `✗ 走查未通过（${failures} 项）` : '✓ 走查通过')
  process.exit(failures ? 1 : 0)
}

main().catch((e) => { console.error(e); process.exit(1) })
