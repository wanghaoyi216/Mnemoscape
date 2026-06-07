import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

/**
 * Vitest config for bugfix exploration property tests + future unit tests.
 *
 * Notes:
 *  - Uses happy-dom (lighter than jsdom) for component-level tests.
 *  - Excludes node_modules + dist as usual; includes anything matching
 *    `tests/**\/*.spec.ts` so the bugfix folder is auto-discovered.
 *  - Globals=true so `describe / it / expect` work without imports.
 */
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'happy-dom',
    globals: true,
    include: ['tests/**/*.spec.ts'],
    css: false,
  },
})
