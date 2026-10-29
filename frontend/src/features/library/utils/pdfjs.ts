import type { PDFDocumentLoadingTask } from 'pdfjs-dist'

/** Absolute URL of pdf.js's runtime files on the app's own origin (vite.config.ts, pdfjsAssets). */
function pdfjsAssetUrl(path: string): string {
  return new URL(`${import.meta.env.BASE_URL}${__PDFJS_ASSET_DIR__}/${path}`, document.baseURI).href
}

/**
 * The options every PDF opens with. pdf.js 6 has no eval-based font path left, and scripting
 * exists only in its PDFViewer's scripting manager, which this viewer never loads - so an original
 * runs no JavaScript. XFA forms stay off, WebAssembly too: the app's CSP (`script-src 'self'`)
 * allows no `wasm-unsafe-eval`. JBIG2/CCITT and JPEG 2000 images - typical of scans - then decode
 * with the JavaScript fallbacks under `wasmUrl`, served from the app's own origin.
 */
export function pdfLoadOptions() {
  return { enableXfa: false, useWasm: false, wasmUrl: pdfjsAssetUrl('wasm/') } as const
}

/**
 * Loads pdf.js on first use - it stays out of the main bundle - with its worker served from the
 * app's own origin, and starts opening `data` as a PDF. The caller awaits the task's `promise` and
 * ends it with `destroy()`, which also stops the worker.
 */
export async function loadPdf(data: ArrayBuffer): Promise<PDFDocumentLoadingTask> {
  const pdfjs = await import('pdfjs-dist')
  const workerSrc = pdfjsAssetUrl('pdf.worker.min.js')
  if (pdfjs.GlobalWorkerOptions.workerSrc !== workerSrc) {
    pdfjs.GlobalWorkerOptions.workerSrc = workerSrc
  }
  // pdf.js transfers the buffer to its worker, so it gets a copy and the caller keeps its own
  return pdfjs.getDocument({ ...pdfLoadOptions(), data: new Uint8Array(data.slice(0)) })
}

/**
 * pdf.js together with its viewer components. The viewer module reads pdf.js from
 * `globalThis.pdfjsLib`, so that is set before it loads.
 */
export async function loadPdfViewer() {
  const pdfjs = await import('pdfjs-dist')
  ;(globalThis as { pdfjsLib?: unknown }).pdfjsLib = pdfjs
  const viewer = await import('pdfjs-dist/web/pdf_viewer.mjs')
  return { pdfjs, viewer }
}
