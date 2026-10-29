import type { PDFDocumentLoadingTask } from 'pdfjs-dist'

/**
 * The options every PDF opens with. pdf.js 6 has no eval-based font path left, and scripting
 * exists only in its PDFViewer's scripting manager, which this viewer never loads - so an original
 * runs no JavaScript. XFA forms stay off, WebAssembly too: the app's CSP (`script-src 'self'`)
 * allows no `wasm-unsafe-eval`, and pdf.js falls back to its JavaScript decoders.
 */
export const PDF_LOAD_OPTIONS = {
  enableXfa: false,
  useWasm: false,
} as const

/**
 * Loads pdf.js on first use - it stays out of the main bundle - with its worker served from the
 * app's own origin, and starts opening `data` as a PDF. The caller awaits the task's `promise` and
 * ends it with `destroy()`, which also stops the worker.
 */
export async function loadPdf(data: ArrayBuffer): Promise<PDFDocumentLoadingTask> {
  const [pdfjs, worker] = await Promise.all([
    import('pdfjs-dist'),
    import('pdfjs-dist/build/pdf.worker.min.mjs?url'),
  ])
  if (pdfjs.GlobalWorkerOptions.workerSrc !== worker.default) {
    pdfjs.GlobalWorkerOptions.workerSrc = worker.default
  }
  // pdf.js transfers the buffer to its worker, so it gets a copy and the caller keeps its own
  return pdfjs.getDocument({ ...PDF_LOAD_OPTIONS, data: new Uint8Array(data.slice(0)) })
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
