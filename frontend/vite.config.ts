/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// TODO: Add code-splitting (lazy-load MUI, pages) to reduce bundle size below 500 kB
// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    // #707: Vite inlines assets below 4 KB as data: URIs. The small Quicksand subsets
    // (cyrillic, vietnamese, …) fall under that limit and ended up as data: fonts in the
    // CSS - blocked by nginx's strict `font-src 'self'` CSP on every page. Fonts are
    // always emitted as files instead of loosening the CSP to `data:`; everything else
    // keeps the default limit.
    assetsInlineLimit: (filePath) => (/\.(woff2?|ttf|otf|eot)$/.test(filePath) ? false : undefined),
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
})
