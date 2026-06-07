import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  // Load environment variables from the current directory
  const env = loadEnv(mode, process.cwd(), '')
  const backendHost = env.VITE_BACKEND_HOST || 'localhost'

  return {
    plugins: [vue()],
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

            if (/[\\/]node_modules[\\/](vue|vue-router|pinia|vue-i18n)[\\/]/.test(id)) {
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
            if (/[\\/]node_modules[\\/](axios|howler)[\\/]/.test(id)) {
              return 'vendor-runtime'
            }
            return undefined
          },
        },
      },
    },
  }
})
