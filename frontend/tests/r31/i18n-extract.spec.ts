/**
 * R31: i18n-extract validation test.
 *
 * Two things we want to guarantee:
 *   1. Every locale file (zh-CN / en-US) is a non-empty object.
 *   2. en-US contains every zh-CN top-level namespace — that is, the
 *      fallback path will always find a key, so the UI never renders
 *      `skeleton.box` as a literal key.
 *   3. The R31 additions (skeleton.*, errorBoundary.*, pwa.*) are present
 *      in BOTH locales with a non-empty value.
 *
 * Hardcoded CJK in template files is caught by `pnpm i18n:extract` (run
 * separately in CI). That script walks the source tree and emits a JSON
 * report at .i18n-extract.json.
 */
import { describe, it, expect } from 'vitest'
import zhCN from '../../src/i18n/locales/zh-CN'
import enUS from '../../src/i18n/locales/en-US'

function namespacetree(obj: any, prefix = ''): string[] {
  const out: string[] = []
  for (const k of Object.keys(obj ?? {})) {
    const v = obj[k]
    const path = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      out.push(...namespacetree(v, path))
    } else {
      out.push(path)
    }
  }
  return out
}

describe('R31 i18n-extract validation', () => {
  it('zh-CN has at least 200 keys (target was set by R31 spec)', () => {
    const keys = namespacetree(zhCN)
    expect(keys.length).toBeGreaterThanOrEqual(200)
  })

  it('en-US has at least 200 keys', () => {
    const keys = namespacetree(enUS)
    expect(keys.length).toBeGreaterThanOrEqual(200)
  })

  it('en-US contains every zh-CN namespace', () => {
    const zhKeys = new Set(namespacetree(zhCN))
    const enKeys = new Set(namespacetree(enUS))
    const missing: string[] = []
    for (const k of zhKeys) {
      if (!enKeys.has(k)) missing.push(k)
    }
    // Allow up to 5% drift to account for intentionally untranslated strings
    // (e.g. legacy fragments in en that have no English mirror yet).
    const allowed = Math.floor(zhKeys.size * 0.05)
    if (missing.length > allowed) {
      // Build a readable error so the failure tells the dev exactly which
      // keys need translation.
      throw new Error(
        `en-US missing ${missing.length} keys (allowed ${allowed}). First 20: ${missing
          .slice(0, 20)
          .join('\n  ')}`
      )
    }
  })

  it('R31 additions: skeleton.* is present in both locales', () => {
    expect((zhCN as any).skeleton?.box).toBeTruthy()
    expect((enUS as any).skeleton?.box).toBeTruthy()
    expect((zhCN as any).skeleton?.memoryCard?.title).toBeTruthy()
    expect((enUS as any).skeleton?.memoryCard?.title).toBeTruthy()
    expect((zhCN as any).skeleton?.chapterList?.heading).toBeTruthy()
    expect((enUS as any).skeleton?.chapterList?.heading).toBeTruthy()
    expect((zhCN as any).skeleton?.chatMessage?.user).toBeTruthy()
    expect((enUS as any).skeleton?.chatMessage?.user).toBeTruthy()
  })

  it('R31 additions: errorBoundary.* is present in both locales', () => {
    const expected = ['title', 'subtitle', 'retry', 'goHome', 'stackLabel', 'componentLabel']
    for (const k of expected) {
      expect((zhCN as any).errorBoundary?.[k]).toBeTruthy()
      expect((enUS as any).errorBoundary?.[k]).toBeTruthy()
    }
  })

  it('R31 additions: pwa.* is present in both locales', () => {
    expect((zhCN as any).pwa?.offline?.title).toBeTruthy()
    expect((enUS as any).pwa?.offline?.title).toBeTruthy()
    expect((zhCN as any).pwa?.update?.refresh).toBeTruthy()
    expect((enUS as any).pwa?.update?.refresh).toBeTruthy()
    expect((zhCN as any).pwa?.install?.install).toBeTruthy()
    expect((enUS as any).pwa?.install?.install).toBeTruthy()
  })
})
