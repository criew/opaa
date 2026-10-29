import { createReadStream, readFileSync, readdirSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import type { Plugin } from 'vite'

const require = createRequire(import.meta.url)
const PDFJS_ROOT = dirname(require.resolve('pdfjs-dist/package.json'))
const PDFJS_VERSION: string = JSON.parse(
  readFileSync(join(PDFJS_ROOT, 'package.json'), 'utf8'),
).version

/**
 * Where pdf.js's worker and its JavaScript image decoders are served, below /assets/ and named by
 * the pdf.js version, so nginx's immutable caching of /assets/ holds for them as well.
 */
export const PDFJS_ASSET_DIR = `assets/pdfjs-${PDFJS_VERSION}`

/**
 * The files pdf.js loads at runtime besides its bundled code: the worker, emitted as `.js` so it is
 * served as JavaScript whatever nginx's mime table says about `.mjs`, and the JavaScript fallbacks
 * of the JBIG2/CCITT and JPEG 2000 decoders that run instead of WebAssembly (`useWasm: false`),
 * with their licenses. Keys are paths below {@link PDFJS_ASSET_DIR}.
 */
function pdfjsFiles(): Record<string, string> {
  const files: Record<string, string> = {
    'pdf.worker.min.js': join(PDFJS_ROOT, 'build', 'pdf.worker.min.mjs'),
  }
  for (const name of readdirSync(join(PDFJS_ROOT, 'wasm'))) {
    if (name.endsWith('_nowasm_fallback.js') || name.startsWith('LICENSE')) {
      files[`wasm/${name}`] = join(PDFJS_ROOT, 'wasm', name)
    }
  }
  return files
}

/** Serves the pdf.js runtime files in development and emits them into the build. */
export function pdfjsAssets(): Plugin {
  const files = pdfjsFiles()
  return {
    name: 'opaa-pdfjs-assets',
    configureServer(server) {
      server.middlewares.use(`/${PDFJS_ASSET_DIR}/`, (req, res, next) => {
        const source = files[(req.url ?? '').split('?')[0].replace(/^\//, '')]
        if (!source) return next()
        res.setHeader('Content-Type', source.includes('LICENSE') ? 'text/plain' : 'text/javascript')
        createReadStream(source).pipe(res)
      })
    },
    generateBundle() {
      for (const [path, source] of Object.entries(files)) {
        this.emitFile({
          type: 'asset',
          fileName: `${PDFJS_ASSET_DIR}/${path}`,
          source: readFileSync(source),
        })
      }
    },
  }
}
