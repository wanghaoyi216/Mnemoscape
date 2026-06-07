/**
 * Bug condition exploration test — Bug 2 (Map fakery) sub-property F3-coords.
 *
 * POST-FIX INVARIANT (Property F3):
 *   For ANY MemoryLocation string the user has stored, the resolved
 *   coordinates SHALL come from a real backend API
 *   (`/api/v1/memories?withCoords=true` or `/api/v1/users/me/location`)
 *   — NOT from a hard-coded client-side gazetteer literal.
 *
 *   Concretely, post-fix:
 *     (a) `MemoryAtlasView.vue` must NOT contain a `CITY_GAZETTEER` literal.
 *     (b) The view must reference at least one of the real atlas endpoints.
 *     (c) Coordinate resolution must NOT be a static `geocode()` table lookup.
 *
 * Counterexample we expect to record on the CURRENT (unfixed) code:
 *   - `CITY_GAZETTEER` literal IS present (~50 hard-coded city → coord pairs).
 *   - NONE of the four required atlas endpoints is referenced.
 *   - The `geocode(loc)` function only consults the literal — for ANY input it
 *     either returns a verbatim coord pair from the table or null. We use
 *     fast-check to drive a counterexample showing this client-table sourcing.
 *
 * Validates: Requirements 2.1, 2.2, 2.3, 2.6, 3.1, 3.2 (post-fix form).
 */
import { describe, it, expect } from 'vitest'
import * as fc from 'fast-check'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const ATLAS_VIEW_PATH = resolve(__dirname, '../../src/views/MemoryAtlasView.vue')
const ATLAS_SOURCE = readFileSync(ATLAS_VIEW_PATH, 'utf8')

/** Endpoints the FIXED implementation must call. At least one of these in
 *  the source means the fix is at least partially in place. */
const REQUIRED_BACKEND_ENDPOINTS = [
  '/api/v1/users/me/location',
  '/api/v1/memories?withCoords',
  '/api/v1/memories/route',
  '/api/v1/atlas/others',
]

/** Extract the `CITY_GAZETTEER` literal as a JS object via Function eval.
 *  We deliberately do NOT import the .vue file (it would pull Three.js,
 *  Pinia, vue-i18n etc.). Static extraction is enough to demonstrate the
 *  bug condition. */
function extractGazetteerOrNull(): Record<string, [number, number]> | null {
  const m = ATLAS_SOURCE.match(
    /const\s+CITY_GAZETTEER\s*:\s*Record<[^>]+>\s*=\s*(\{[\s\S]*?\n\})/,
  )
  if (!m) return null
  // eslint-disable-next-line @typescript-eslint/no-implied-eval, no-new-func
  return new Function(`return (${m[1]});`)() as Record<string, [number, number]>
}

/** Mirror of MemoryAtlasView's `geocode` — we evaluate it ourselves so the
 *  property test runs without mounting the SFC. */
function buildGeocode(
  gazetteer: Record<string, [number, number]>,
): (loc?: string | null) => [number, number] | null {
  return (loc) => {
    if (!loc) return null
    const norm = loc.trim().toLowerCase().replace(/\s+/g, '')
    for (const [k, v] of Object.entries(gazetteer)) {
      if (norm.includes(k.toLowerCase())) return v
    }
    return null
  }
}

describe('[bugfix-exploration] MemoryAtlasView coord resolution', () => {
  it('post-fix: MemoryAtlasView must NOT define a CITY_GAZETTEER literal', () => {
    // Counterexample on current code: CITY_GAZETTEER IS present → this assertion
    // FAILS, which is the success signal for an exploration test.
    expect(ATLAS_SOURCE).not.toMatch(/const\s+CITY_GAZETTEER\s*:\s*Record/)
  })

  it('post-fix: MemoryAtlasView must reference at least one real atlas backend endpoint', () => {
    const found = REQUIRED_BACKEND_ENDPOINTS.filter((ep) => ATLAS_SOURCE.includes(ep))
    // Counterexample on current code: zero endpoints found → this FAILS.
    expect(found.length).toBeGreaterThan(0)
  })

  it('post-fix property: arbitrary MemoryLocation strings should NOT all collapse onto a static client table', () => {
    const gazetteer = extractGazetteerOrNull()
    // If the gazetteer no longer exists in the source, the prior test has
    // already failed. We still want THIS test to record a counterexample on
    // the current code, so when the gazetteer is present we drive fast-check
    // to expose the client-table sourcing.
    if (gazetteer === null) {
      // Post-fix branch: nothing to do at this static layer; the absence of
      // the gazetteer is the property holding.
      expect(true).toBe(true)
      return
    }

    const geocode = buildGeocode(gazetteer)
    const allCoordValues = new Set(
      Object.values(gazetteer).map((v) => `${v[0]},${v[1]}`),
    )

    // Drive fast-check to find ANY input where the resolved coord is NOT in
    // the static table — a real backend would frequently produce such coords
    // (street-level, freshly geocoded, never seen in a 50-row table).
    fc.assert(
      fc.property(
        fc.oneof(
          fc.constantFrom(...Object.keys(gazetteer)),
          fc.string({ minLength: 0, maxLength: 24 }),
          fc.constantFrom(
            '北京', '上海', '大理', 'tokyo', 'paris', 'unknown-place', '',
          ),
        ),
        (loc) => {
          const coords = geocode(loc as string | null)
          if (coords === null) return true // null is allowed (unmapped)
          // POST-FIX EXPECTATION: at least some non-null answers should NOT be
          // verbatim entries from the static literal — i.e. there should
          // exist an input that produces an "off-table" coord.
          // We *want* to find a counterexample to this property holding-as-true,
          // so the property body returns true only when the answer is NOT in
          // the table. fast-check will shrink to a falsifying input on
          // current code (which always returns table entries).
          return !allCoordValues.has(`${coords[0]},${coords[1]}`)
        },
      ),
      { numRuns: 200 },
    )
  })
})
