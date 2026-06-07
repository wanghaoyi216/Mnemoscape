/**
 * Atlas zoom -> drilldown level resolver.
 *
 * 设计书 v1.1 §3.3 要求的五级下钻：
 *   World    (zoom < 3)
 *   Country  (3 .. 5)
 *   Province (5 .. 7)
 *   City     (7 .. 10)
 *   County   (>= 10)
 *
 * 当 zoom > 12 时进一步触发 3D 倾斜 (pitch=60°)。
 *
 * 把这套阈值单独抽出来便于单元测试 (frontend/tests/atlas-zoom.spec.ts)
 * 也避免把字面量到处复制。
 */
export type DrilldownLevel = 'world' | 'country' | 'province' | 'city' | 'county'

export const DRILL_THRESHOLDS = {
  world: 3,
  country: 5,
  province: 7,
  city: 10,
  // > 10 = county
  tilt: 12,
} as const

export function resolveLevel(zoom: number): DrilldownLevel {
  if (zoom < DRILL_THRESHOLDS.world) return 'world'
  if (zoom < DRILL_THRESHOLDS.country) return 'country'
  if (zoom < DRILL_THRESHOLDS.province) return 'province'
  if (zoom < DRILL_THRESHOLDS.city) return 'city'
  return 'county'
}

/** 用 zoom 决定相机是否应该自动倾斜（pitch=60°） */
export function shouldTilt(zoom: number): boolean {
  return zoom > DRILL_THRESHOLDS.tilt
}

/** 给定级别返回一个粗糙的"聚合半径"（km）— 用于 ScatterplotLayer 的 radius mapping */
export function aggregationRadiusKm(level: DrilldownLevel): number {
  switch (level) {
    case 'world':    return 600
    case 'country':  return 240
    case 'province': return 90
    case 'city':     return 35
    case 'county':   return 12
    default:         return 35
  }
}
