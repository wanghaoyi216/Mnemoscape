/**
 * R31: typed PWA manifest served from /manifest.webmanifest.
 * Vite-plugin-pwa will copy this file into the build output unchanged.
 */
export interface PwaIcon {
  src: string
  sizes: string
  type: string
  purpose?: 'any' | 'maskable' | 'any maskable'
}

export interface PwaShortcut {
  name: string
  short_name: string
  description: string
  url: string
  icons: PwaIcon[]
}

export interface PwaManifest {
  name: string
  short_name: string
  description: string
  lang: string
  dir: 'ltr' | 'rtl' | 'auto'
  start_url: string
  scope: string
  display: 'standalone' | 'fullscreen' | 'minimal-ui' | 'browser'
  display_override?: Array<'window-controls-overlay' | 'standalone' | 'browser'>
  orientation: 'any' | 'natural' | 'portrait' | 'landscape'
  background_color: string
  theme_color: string
  categories: string[]
  icons: PwaIcon[]
  shortcuts: PwaShortcut[]
}

export const pwaManifest: PwaManifest = {
  name: 'Mnemoscape — Memory Museum',
  short_name: 'Mnemoscape',
  description: 'AI-driven personal memory museum — reconstruct written memories into explorable 3D scenes.',
  lang: 'zh-CN',
  dir: 'ltr',
  start_url: '/',
  scope: '/',
  display: 'standalone',
  display_override: ['window-controls-overlay', 'standalone', 'browser'],
  orientation: 'any',
  background_color: '#0a0c10',
  theme_color: '#0a0c10',
  categories: ['productivity', 'lifestyle', 'art'],
  icons: [
    { src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' },
    { src: '/favicon.ico', sizes: '64x64', type: 'image/x-icon', purpose: 'any' },
    { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any maskable' },
    { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any maskable' }
  ],
  shortcuts: [
    {
      name: 'My memories',
      short_name: 'Memories',
      description: 'Open your memory list',
      url: '/memories',
      icons: [{ src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml' }]
    },
    {
      name: 'New memory',
      short_name: 'Create',
      description: 'Capture a new memory',
      url: '/memories/new',
      icons: [{ src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml' }]
    },
    {
      name: 'Atlas',
      short_name: 'Atlas',
      description: 'Memory map of the world',
      url: '/memories/atlas',
      icons: [{ src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml' }]
    }
  ]
}
