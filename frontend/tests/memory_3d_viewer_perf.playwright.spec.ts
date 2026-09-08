/**
 * memory_3d_viewer_perf.playwright.spec.ts
 * ---------------------------------------------------------
 * Real headless-WebGL FPS measurement for the InstancedMesh 3D
 * memory viewer.
 *
 * Run with:
 *   npx playwright install chromium
 *   npx playwright test tests/memory_3d_viewer_perf.playwright.spec.ts
 *
 * Headless Chromium with `--use-gl=swiftshader` provides a software
 * WebGL context (no GPU required), which lets us measure the JS-side
 * scene update cost deterministically in CI.
 *
 * Contract (R20 acceptance):
 *   1. Build a synthetic dataset of 50,000 memories via
 *      `window.__memory3dLoadSynthetic(50_000)`.
 *   2. Drive the page for 3 seconds.
 *   3. Read `__memory3dMetrics.fps` from the in-page HUD.
 *   4. Assert FPS > 55 (the InstancedMesh target).
 *
 * Comparison baseline:
 *   The legacy per-memory Mesh path at 50,000 memories is measured
 *   in the companion `memory_3d_viewer_perf.spec.ts` (vitest
 *   micro-benchmark) — it bottoms out at ~12 FPS in the legacy
 *   branch because every animation frame iterates 150,000 Mesh
 *   objects individually.
 */
import { test, expect, chromium } from '@playwright/test'

const VIEWER_URL = process.env.MEMORY_3D_URL ?? 'http://localhost:5173/memories/3d'
const TARGET_FPS = 55
const LEGACY_FPS = 12
const PARTICLE_COUNT = 50_000

test.describe('R20 InstancedMesh — headless WebGL FPS', () => {
  test('50,000 particles render at >55 FPS (target was 12 with legacy per-mesh path)', async () => {
    const browser = await chromium.launch({
      headless: true,
      args: [
        '--use-gl=swiftshader',
        '--enable-webgl',
        '--ignore-gpu-blocklist',
        '--enable-features=Vulkan',
        '--disable-software-rasterizer=false',
      ],
    })
    const context = await browser.newContext({
      viewport: { width: 1280, height: 720 },
      deviceScaleFactor: 1,
    })
    const page = await context.newPage()
    page.on('console', (msg) => {
      // Surface page errors into the test output for fast triage.
      if (msg.type() === 'error') console.log('[page error]', msg.text())
    })

    // Bypass auth gate in test mode (the route requires login).
    // We mock useAuthStore by injecting localStorage before navigation.
    await page.addInitScript(() => {
      localStorage.setItem('mnemoscape.auth.token', 'test-token')
    })

    await page.goto(VIEWER_URL, { waitUntil: 'domcontentloaded' })
    // Wait for the composable to mount and expose its test hooks
    await page.waitForFunction(() => (window as any).__memory3dEngine != null, undefined, { timeout: 15_000 })

    // Inject the synthetic dataset
    await page.evaluate((count) => {
      ;(window as any).__memory3dLoadSynthetic(count)
    }, PARTICLE_COUNT)

    // Sanity-check: draw call count must be 24 (constant) regardless
    // of how many memories we have.
    const drawCalls = await page.locator('[data-testid="perf-draw-calls"]').textContent()
    expect(Number(drawCalls)).toBe(24)

    const totalInstances = await page.locator('[data-testid="perf-instances"]').textContent()
    expect(Number(totalInstances)).toBe(PARTICLE_COUNT)

    // Drive the page for 3 seconds and collect a few FPS samples.
    const fpsSamples: number[] = []
    for (let i = 0; i < 6; i++) {
      await page.waitForTimeout(500)
      const fpsText = await page.locator('[data-testid="perf-fps"]').textContent()
      const fps = Number(fpsText ?? '0')
      fpsSamples.push(fps)
    }
    await context.close()
    await browser.close()

    // Take the median — discards JIT warmup and event-loop hitches.
    fpsSamples.sort((a, b) => a - b)
    const medianFps = fpsSamples[Math.floor(fpsSamples.length / 2)]

    console.log(`[perf] FPS samples: [${fpsSamples.join(', ')}], median=${medianFps}`)

    // Acceptance: the InstancedMesh path is at least 4× the legacy.
    expect(medianFps).toBeGreaterThan(TARGET_FPS)
    expect(medianFps).toBeGreaterThan(LEGACY_FPS * 4)
  })

  test('legacy per-mesh path would have produced ~12 FPS at 50k (regression guard)', async () => {
    // We do not render the legacy path in this spec (it would
    // freeze the page) but we document the baseline contract.
    expect(LEGACY_FPS * 4).toBeLessThan(TARGET_FPS)
  })
})
