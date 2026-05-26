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
          target: `ws://${backendHost}:8084`,
          changeOrigin: true,
          ws: true,
        },
      },
    },
  }
})
