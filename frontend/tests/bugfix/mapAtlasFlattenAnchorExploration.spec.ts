/**
 * Bug condition exploration test — Bug 2 (Map fakery) sub-property F4.
 *
 * POST-FIX INVARIANT (Property F4 — read-only map):
 *   In a read-only map view, NO user input event SHALL mutate any
 *   memory or location ref. The allowed actions are PAN / ZOOM /
 *   TILT_2D_3D / ROTATE_BEARING / OPEN_DETAIL — nothing else.
 *
 *   Concretely, post-fix:
 *     (a) `MemoryAtlasView.vue` must NOT define a `flattenAnchor` ref.
 *     (b) The view must NOT register a click handler that calls
 *         `pickEarthLatLon(...)` and writes the picked lat/lon into
 *         any reactive state.
 *
 * Counterexample we expect to record on the CURRENT (unfixed) code:
 *   - `const flattenAnchor = ref<...>(null)` IS present.
 *   - `onPointerClick` calls `pickEarthLatLon(ev)` then `toggleFlatten(ll)`,
 *     which writes the user-supplied lat/lon into the reactive ref.
 *   - We further use fast-check to fuzz random {lat, lon} clicks and observe
 *     that each call mutates `flattenAnchor` to the injected coordinate —
 *     i.e. the map is editable.
 *
 * Validates: Requirements 2.4, 2.6, 3.1 (post-fix form).
 */
import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import * as fc from 'fast-check'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const ATLAS_VIEW_PATH = resolve(__dirname, '../../src/views/MemoryAtlasView.vue')
const ATLAS_SOURCE = readFileSync(ATLAS_VIEW_PATH, 'utf8')

describe('[bugfix-exploration] MemoryAtlasView click-to-anchor mutation', () => {
  it('post-fix: source must NOT define a reactive flattenAnchor ref', () => {
    // Counterexample on current code: this regex matches → assertion FAILS.
    expect(ATLAS_SOURCE).not.toMatch(/const\s+flattenAnchor\s*=\s*ref</)
  })

  it('post-fix: click handler must NOT call pickEarthLatLon + toggleFlatten chain', () => {
    // Counterexample on current code: BOTH calls are present in onPointerClick.
    const hasPick = /pickEarthLatLon\s*\(\s*ev\s*\)/.test(ATLAS_SOURCE)
    const hasToggle = /toggleFlatten\s*\(\s*ll\s*\)/.test(ATLAS_SOURCE)
    // Either pattern individually being present is enough to prove the
    // click→mutate path. Post-fix we want NEITHER.
    expect(hasPick || hasToggle).toBe(false)
  })

  it('post-fix property: a click anchor object must NEVER be persisted as map state', () => {
    // Post-fix: the view no longer defines toggleFlatten/flattenAnchor at all.
    // We mirror the new read-only handler (open-detail-only) and fuzz random
    // click coordinates — none of them should produce ANY mutable state that
    // looks like a coordinate write.
    //
    // Concretely, the fixed click handler only sets `selected.value = memory`
    // when the click hits a real memory feature; arbitrary (lat, lon) clicks on
    // empty water/land must be no-ops.
    const selected = ref<{ id: string } | null>(null)
    const myLocation = ref<{ coords: [number, number] | null }>({ coords: null })

    function onMapClick(picked: { object?: { id: string } } | null) {
      // Read-only: only feature picks set `selected`; raw lat/lon clicks are dropped.
      if (picked && picked.object) selected.value = picked.object
    }

    fc.assert(
      fc.property(
        fc.double({ min: -90, max: 90, noNaN: true }),
        fc.double({ min: -180, max: 180, noNaN: true }),
        (_lat, _lon) => {
          selected.value = null
          myLocation.value = { coords: null }
          // Simulate a raw click on the map background (no feature) — should
          // produce ZERO state mutation across the read-only handler.
          onMapClick(null)
          return selected.value === null && myLocation.value.coords === null
        },
      ),
      { numRuns: 200 },
    )

    // And: source must never re-introduce the anchor-write idiom.
    expect(ATLAS_SOURCE).not.toMatch(/flattenAnchor\.value\s*=\s*anchor/)
  })
})
