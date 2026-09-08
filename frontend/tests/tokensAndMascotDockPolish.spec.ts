import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'

const TOKENS_PATH = resolve(__dirname, '../src/assets/styles/tokens.scss')
const STYLE_PATH = resolve(__dirname, '../src/style.css')
const MAIN_PATH = resolve(__dirname, '../src/main.ts')
const DOCK_PATH = resolve(__dirname, '../src/components/ai/AiMascotDock.vue')
const I_DOCK_PATH = resolve(__dirname, '../src/components/ai/iMascotDock.vue')

describe('Milestone 4 (Task 6): Glassmorphism Design Tokens & Frontend Productization Polish', () => {
  it('1.1 should have tokens.scss with complete canvas, glassmorphism, motion, elevation, and status variables', () => {
    expect(existsSync(TOKENS_PATH)).toBe(true)
    const tokensContent = readFileSync(TOKENS_PATH, 'utf8')

    // Canvas surfaces
    expect(tokensContent).toContain('--bg-0')
    expect(tokensContent).toContain('--bg-1')
    expect(tokensContent).toContain('--bg-2')
    expect(tokensContent).toContain('--bg-3')
    expect(tokensContent).toContain('$bg-canvas')
    expect(tokensContent).toContain('--bg-canvas')

    // Apple HIG Glassmorphism tokens
    expect(tokensContent).toContain('--glass-surface')
    expect(tokensContent).toContain('--glass-border')
    expect(tokensContent).toContain('--glass-glow')
    expect(tokensContent).toContain('--glass-inner-highlight')
    expect(tokensContent).toContain('--glass-blur-sm')
    expect(tokensContent).toContain('--glass-blur-md')
    expect(tokensContent).toContain('--glass-blur-lg')
    expect(tokensContent).toContain('--glass-blur-xl')
    expect(tokensContent).toContain('--glass-saturate')

    // Motion curves
    expect(tokensContent).toContain('--spring-bounce')
    expect(tokensContent).toContain('--spring-smooth')
    expect(tokensContent).toContain('--duration-fast')
    expect(tokensContent).toContain('--duration-base')
    expect(tokensContent).toContain('--duration-slow')

    // Apple HIG Elevation Hierarchy
    expect(tokensContent).toContain('--elevation-1')
    expect(tokensContent).toContain('--elevation-2')
    expect(tokensContent).toContain('--elevation-3')
    expect(tokensContent).toContain('--elevation-4')
    expect(tokensContent).toContain('--elevation-5')

    // Semantic & Workflow tokens
    expect(tokensContent).toContain('--status-pending')
    expect(tokensContent).toContain('--status-running')
    expect(tokensContent).toContain('--status-completed')
    expect(tokensContent).toContain('--status-failed')
    expect(tokensContent).toContain('--subagent-chip-bg')
    expect(tokensContent).toContain('--verdict-pass-bg')
    expect(tokensContent).toContain('--verdict-fail-bg')
  })

  it('1.2 should integrate tokens.scss into style.css and main entry point', () => {
    const styleContent = readFileSync(STYLE_PATH, 'utf8')
    expect(styleContent).toMatch(/@import\s+['"][^'"]*tokens\.scss['"]/)

    const mainContent = readFileSync(MAIN_PATH, 'utf8')
    expect(mainContent).toMatch(/import\s+['"][^'"]*tokens\.scss['"]/)
  })

  it('2.1 should have floating breathing animation and dynamic glow on 3D particle sphere in AiMascotDock', () => {
    const dockContent = readFileSync(DOCK_PATH, 'utf8')

    // Floating breathing animation
    expect(dockContent).toContain('orbFloat')
    expect(dockContent).toContain('orbBreath')
    expect(dockContent).toContain('orbColorSpin')
    expect(dockContent).toContain('ai-orb__particles')
    expect(dockContent).toContain('ai-orb__ribbon')
    expect(dockContent).toContain('ai-orb__halo')
  })

  it('2.2 should have multi-agent workflow mesh with connectors, pulsing status chips, and subagent chips', () => {
    const dockContent = readFileSync(DOCK_PATH, 'utf8')

    // Multi-agent mesh and vertical connector
    expect(dockContent).toContain('ai-workflow-mesh')
    expect(dockContent).toContain('ai-mesh-step__connector')
    expect(dockContent).toContain('ai-mesh-step__badge')
    expect(dockContent).toContain('ai-mesh-step__chip')
    expect(dockContent).toContain('ai-mesh-step__chip-pulse')

    // Subagent chips
    expect(dockContent).toContain('ai-subagent-chip')
    expect(dockContent).toContain('ai-subagent-chip__pulse')
    expect(dockContent).toContain('ai-subagent-chip__status')
  })

  it('2.3 should have ReAct spinning gear indicator and acceptance verdict card styling', () => {
    const dockContent = readFileSync(DOCK_PATH, 'utf8')

    // ReAct gear tool indicators
    expect(dockContent).toContain('ai-tool__gear')
    expect(dockContent).toContain('ai-tool__gear--spin')
    expect(dockContent).toContain('ai-tool__status-chip')

    // Acceptance Agent verdict cards
    expect(dockContent).toContain('ai-acceptance-box')
    expect(dockContent).toContain('ai-acceptance-badge')
    expect(dockContent).toContain('ai-acceptance-header')
  })

  it('2.4 should have glassmorphism slide-out chat panel with blur(24px) saturate(180%) and Apple spring transitions', () => {
    const dockContent = readFileSync(DOCK_PATH, 'utf8')

    expect(dockContent).toContain('backdrop-filter: blur(24px) saturate(180%)')
    expect(dockContent).toContain('--spring-bounce')
    expect(dockContent).toContain('--spring-smooth')
    expect(dockContent).toContain('dock-panel-enter-active')
  })

  it('2.5 should provide iMascotDock.vue compatibility component alias', () => {
    expect(existsSync(I_DOCK_PATH)).toBe(true)
    const iDockContent = readFileSync(I_DOCK_PATH, 'utf8')
    expect(iDockContent).toContain('AiMascotDock')
  })
})
