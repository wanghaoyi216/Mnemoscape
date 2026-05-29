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
  }
})
