/**
 * R31: i18n-extract — scans .vue / .ts files for hardcoded Chinese strings
 * that are NOT wrapped in t('...') or already inside a script constant.
 *
 * Heuristic: any CJK Unified Ideograph run (length >= 2) in a <template> block
 * is considered a candidate. We do NOT try to be perfect — false positives
 * inside brand names, Sentry DSNs or code comments are acceptable as long as
 * the developer can scan the report.
 *
 * Outputs:
 *   - .i18n-extract.json   (machine-readable)
 *   - stdout summary        (developer-facing)
 *
 * Usage: pnpm i18n:extract
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const root = path.join(__dirname, '..', 'src')

// Recursively walk src/, collect .vue and .ts files.
function walk(dir) {
  const out = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === 'dist') continue
      out.push(...walk(full))
    } else if (/\.(vue|ts)$/.test(entry.name)) {
      out.push(full)
    }
  }
  return out
}

const cjkRun = /[一-鿿]{2,}/g
// Ignore patterns — i18n() calls, console.log debug, brand subtitle strings
// already wrapped in t(), URL fragments, hex colors, import paths.
const linesToIgnore = (line) => {
  if (/from\s+['"]/.test(line)) return true
  if (/import\s+/.test(line)) return true
  if (/\/\//.test(line.trimStart())) return true
  if (/\/\*/.test(line)) return true
  if (/console\.(log|debug)/.test(line)) return true
  if (/\.vue/.test(line) && /script/.test(line)) return true
  return false
}

const findings = []
const files = walk(root)
for (const file of files) {
  const text = fs.readFileSync(file, 'utf8')
  const lines = text.split('\n')
  lines.forEach((line, idx) => {
    // Skip lines that look like t(...) / i18n.t(...) arguments — but only if
    // the CJK run appears INSIDE a quoted string inside such a call. Simple
    // proxy: skip the line if it starts with whitespace + t( or contains
    // t(' ... ') wrapping the run.
    const trimmed = line.trimStart()
    if (/^t\(|^i18n\.t\(|^useI18n\(\)\.t\(/.test(trimmed)) return
    if (/['"`].*t\(['"`]/.test(line) && /['"]\)\s*$/.test(trimmed)) return
    if (linesToIgnore(line)) return

    const matches = line.match(cjkRun)
    if (!matches) return
    findings.push({
      file: path.relative(root, file),
      line: idx + 1,
      excerpt: line.trim().slice(0, 140),
      runs: matches,
    })
  })
}

const summary = {
  scannedFiles: files.length,
  hits: findings.length,
  byFile: {},
}
for (const f of findings) {
  summary.byFile[f.file] = (summary.byFile[f.file] || 0) + 1
}

const outPath = path.join(__dirname, '..', '.i18n-extract.json')
fs.writeFileSync(outPath, JSON.stringify({ summary, findings }, null, 2))

console.log(`[i18n-extract] scanned ${summary.scannedFiles} files`)
console.log(`[i18n-extract] hardcoded CJK runs found: ${summary.hits}`)
console.log(`[i18n-extract] by file:`)
for (const [f, n] of Object.entries(summary.byFile).sort((a, b) => b[1] - a[1])) {
  console.log(`  ${String(n).padStart(4)}  ${f}`)
}
console.log(`[i18n-extract] machine-readable report → ${path.relative(process.cwd(), outPath)}`)

// Exit non-zero only if there are hits in R31 *new* component files
// (ErrorBoundary, SkeletonBox, SkeletonCard). Those should be 100% i18n-wrapped.
const r31New = ['components/common/ErrorBoundary.vue', 'components/common/SkeletonBox.vue', 'components/common/SkeletonCard.vue']
const r31Hits = findings.filter(f => r31New.includes(f.file))
if (r31Hits.length > 0) {
  console.error(`[i18n-extract] FAIL — ${r31Hits.length} hardcoded CJK runs in R31 new components`)
  process.exit(1)
}
process.exit(0)
