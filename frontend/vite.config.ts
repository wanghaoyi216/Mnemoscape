import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'
import { pwaManifest } from './src/pwa/manifest'

export default defineConfig(({ mode }) => {
  // Load environment variables from the current directory
  const env = loadEnv(mode, process.cwd(), '')
  const backendHost = env.VITE_BACKEND_HOST || 'localhost'
  const isDev = mode === 'development'

  return {
    plugins: [
      vue(),
      // R31: PWA — manifest + service worker (injectManifest mode so we can
      // write the SW in TypeScript with full Workbox strategies). Disabled
      // in dev to avoid stale-cache headaches during hot reload.
      VitePWA({
        registerType: 'autoUpdate',
        injectRegister: isDev ? null : 'auto',
        strategies: 'injectManifest',
        srcDir: 'src/pwa',
        filename: 'service-worker.ts',
        manifest: pwaManifest,
        injectManifest: {
          // Webpack-style import.meta.url is fine for Workbox precacheAndRoute.
          globPatterns: ['**/*.{js,css,html,svg,ico,png,woff2,webp}'],
          globIgnores: [
            'media/**',
            'assets/vendor-{map,map-3d,three,charts,mermaid,graph}-*.js',
          ],
          maximumFileSizeToCacheInBytes: 5 * 1024 * 1024,
        },
        devOptions: {
          enabled: isDev,
          type: 'module',
          navigateFallback: 'index.html',
        },
        // Don't precache huge library chunks (deck.gl, three, maplibre) — they
        // get cached at runtime via the SWR/NetworkFirst routes.
        workbox: {
          navigateFallback: '/index.html',
          globPatterns: ['**/*.{js,css,html,svg,ico,png,woff2}'],
        },
      }),
    ],
    css: {
      preprocessorOptions: {
        scss: {
          api: 'modern-compiler',
        },
      },
    },
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: `http://${backendHost}:8080`,
          changeOrigin: true,
        },
        '/ws': {
          // Forward WebSocket handshakes through the gateway (port 8080) so the
          // gateway's `resonance-ws` route (lb:ws://resonance-service) handles
          // both /ws/chat, /ws/resonance, and the new /ws/support endpoints.
          // Earlier this targeted port 8084 (asset-service) which never had a
          // ws handler; chat seemed to "work" only because the UI fell back
          // gracefully on connection failure.
          target: `ws://${backendHost}:8080`,
          changeOrigin: true,
          ws: true,
        },
      },
    },
    build: {
      modulePreload: {
        resolveDependencies(_filename, deps) {
          return deps.filter((dep) => !dep.includes('vendor-mermaid') && !dep.includes('vendor-graph'))
        },
      },
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (!id.includes('node_modules')) return undefined

            if (/[\\/]node_modules[\\/](vue|vue-router|pinia|vue-i18n|workbox-)[\\/]/.test(id)) {
              return 'vendor-vue'
            }
            if (/[\\/]node_modules[\\/](three|@tweenjs)[\\/]/.test(id)) {
              return 'vendor-three'
            }
            if (/[\\/]node_modules[\\/](@deck\.gl|deck\.gl|@luma\.gl|luma\.gl|@math\.gl|math\.gl|probe\.gl|mjolnir\.js)[\\/]/.test(id)) {
              return 'vendor-map-3d'
            }
            if (/[\\/]node_modules[\\/](maplibre-gl|@mapbox|supercluster|geojson-vt)[\\/]/.test(id)) {
              return 'vendor-map'
            }
            if (/[\\/]node_modules[\\/](echarts|zrender|vue-echarts)[\\/]/.test(id)) {
              return 'vendor-charts'
            }
            if (/[\\/]node_modules[\\/](marked|dompurify)[\\/]/.test(id)) {
              return 'vendor-markdown'
            }
            if (/[\\/]node_modules[\\/](mermaid|katex)[\\/]/.test(id)) {
              return 'vendor-mermaid'
            }
            if (/[\\/]node_modules[\\/](cytoscape|dagre|elkjs)[\\/]/.test(id)) {
              return 'vendor-graph'
            }
            if (/[\\/]node_modules[\\/](axios|howler|workbox-)[\\/]/.test(id)) {
              return 'vendor-runtime'
            }
            return undefined
          },
        },
      },
    },
  }
})
