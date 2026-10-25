/// <reference types="vitest/config" />
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolveOpaaVersion } from './opaaVersion.ts'
import { prototypeOutput } from './prototypeOutput.ts'

// TODO: Split MUI out of the entry chunk to bring it below 500 kB
// https://vite.dev/config/
// `vite build --mode prototype` builds the static UI-lab prototype (e2e/ui-lab/README.md): mocks
// answered in the page, hash routing, relative asset paths so it runs from any sub-path, ASCII-only
// scripts and its own page title.
export default defineConfig(({ mode }) => ({
  base: mode === 'prototype' ? './' : '/',
  plugins: [react(), mode === 'prototype' && prototypeOutput()],
  // @/ points at src/, mirroring "paths" in tsconfig.app.json; Vitest inherits it.
  resolve: {
    alias: [{ find: /^@\//, replacement: fileURLToPath(new URL('./src/', import.meta.url)) }],
  },
  // The version shown under "Info zu OPAA"; release builds pass their tag's version as OPAA_VERSION
  // (frontend/Dockerfile), main images 0.0.0-dev+<commit>, every other build - tests included - is
  // 0.0.0-dev.
  define: {
    __OPAA_VERSION__: JSON.stringify(resolveOpaaVersion(process.env.OPAA_VERSION)),
    ...(mode === 'prototype' && {
      'import.meta.env.VITE_ENABLE_MOCKS': JSON.stringify('true'),
      'import.meta.env.VITE_UI_LAB_PROTOTYPE': JSON.stringify('true'),
    }),
  },
  build: {
    // dist/.vite/manifest.json feeds scripts/bundle-report.mjs.
    manifest: true,
    // Vite inlines assets below 4 KB as data: URIs. The small Quicksand subsets
    // (cyrillic, vietnamese, …) fall under that limit and ended up as data: fonts in the
    // CSS - blocked by nginx's strict `font-src 'self'` CSP on every page. Fonts are
    // always emitted as files instead of loosening the CSP to `data:`; everything else
    // keeps the default limit.
    assetsInlineLimit: (filePath) => (/\.(woff2?|ttf|otf|eot)$/.test(filePath) ? false : undefined),
    // A theme's index.ts only re-exports. Marked free of side effects, importing one name from it
    // bundles that module alone, not every other module the barrel re-exports.
    rolldownOptions: {
      treeshake: {
        moduleSideEffects: [
          { test: /[\\/]src[\\/]features[\\/][^\\/]+[\\/]index\.ts$/, sideEffects: false },
        ],
      },
    },
  },
  server: {
    proxy: {
      // The settings page frames this download itself, as the container allows (frontend/nginx.conf);
      // the backend's own X-Frame-Options: DENY would make browsers cancel it.
      '/api/v1/me/self-disclosure/download': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyRes', (response) => {
            delete response.headers['x-frame-options']
          })
        },
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // The MCP endpoint of the external-access channel, reachable through the dev server on the
      // same origin as in the container (frontend/nginx.conf). A prefix, like '/api' above: it
      // covers the path and the spellings the container forwards as well (/mcp;x=1, /mcp/), whose
      // meaning the backend decides.
      '/mcp': {
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
}))
