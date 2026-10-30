import type { ChunkAnchor } from '@/shared/api/types'
import { getDocumentContent } from '../api/libraryApi'

// Shared between the library document list's "Original öffnen" action, the
// citation deep link and the Belegfenster's "Dokument öffnen" action - all three need to
// turn a documentId into a rendered/downloaded/previewed file, and neither can use a plain
// <a href> since the endpoint is Bearer-authenticated (ADR-0005).

// The only content types opened inline as a blob: URL in a new tab. That tab runs in this app's
// origin, where the endpoint's own CSP and X-Content-Type-Options (DocumentController) do not
// apply, so the list is exact: no prefix match, nothing that can carry script (SVG, XHTML or any
// other +xml type, text/xml, application/xml, HTML). Everything else is downloaded instead.
const PREVIEWABLE_CONTENT_TYPES = new Set([
  'application/pdf',
  'image/png',
  'image/jpeg',
  'image/gif',
  'image/webp',
])

// Markdown and plain text are rendered client-side in DocumentTextPreviewDialog instead of being
// opened as a blob: URL. MarkdownRenderer never passes raw HTML through (no rehype-raw) and keeps
// every URL as written (urlTransform keepUrl); script protection comes solely from its own `a` and
// `img` components: only http(s) links to allowed origins become clickable (isAllowedLinkTarget),
// images are never loaded. See DocumentTextPreviewDialog.test.tsx.
const TEXT_PREVIEWABLE_CONTENT_TYPES = ['text/markdown', 'text/plain']

// `blob.text()` below reads the entire file into a JS string - the upload
// limit is 50 MB (LibraryDocumentService), so an unbounded read of a large plain-text/Markdown
// original could freeze the tab. 2 MiB comfortably covers the indexed documents this feature was
// built for (the Klick-Test's `001_personalausweis.md` and the like are a few KB) while staying
// well short of anything that would visibly stall parsing/rendering; a file above it falls back
// to the download branch instead; see {@link isTooLargeForTextPreview}.
export const TEXT_PREVIEW_MAX_BYTES = 2 * 1024 * 1024

// `blob.type` carries the full Content-Type line, parameters included (e.g.
// `image/png; charset=binary`); every check compares only the lower-cased type/subtype.
function contentTypeEssence(contentType: string): string {
  return contentType.split(';')[0].trim().toLowerCase()
}

function isPreviewable(contentType: string): boolean {
  return PREVIEWABLE_CONTENT_TYPES.has(contentTypeEssence(contentType))
}

function isPdf(contentType: string): boolean {
  return contentTypeEssence(contentType) === 'application/pdf'
}

function isTextPreviewable(contentType: string): boolean {
  return TEXT_PREVIEWABLE_CONTENT_TYPES.includes(contentTypeEssence(contentType))
}

function isTooLargeForTextPreview(sizeBytes: number): boolean {
  return sizeBytes > TEXT_PREVIEW_MAX_BYTES
}

// Exported so tests can advance fake timers by exactly this amount instead of a magic number, and
// so a future caller with different lifetime needs (e.g. a longer-lived preview) can reason about
// it explicitly rather than guessing at the delay from behaviour alone.
export const OBJECT_URL_REVOKE_DELAY_MS = 60_000

// Office formats, spreadsheets and mail have no viewer in the browser; „Dokument öffnen“ shows
// their indexed text instead (DocumentTextVersionDialog) and keeps the original one click away.
// Decided by the file name, so the original is not fetched just to learn it gets no preview.
const TEXT_VERSION_EXTENSIONS = new Set([
  'doc',
  'docx',
  'odt',
  'rtf',
  'ppt',
  'pptx',
  'odp',
  'xls',
  'xlsx',
  'ods',
  'csv',
  'eml',
  'msg',
  'htm',
  'html',
])

/** Whether „Dokument öffnen“ shows `fileName`'s Textfassung rather than fetching the original. */
export function opensAsTextVersion(fileName: string): boolean {
  const dot = fileName.lastIndexOf('.')
  return dot >= 0 && TEXT_VERSION_EXTENSIONS.has(fileName.slice(dot + 1).toLowerCase())
}

function triggerDownload(objectUrl: string, fileName: string) {
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = fileName
  link.rel = 'noopener'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}

/**
 * What {@link openDocumentContent} actually did with the file, so a caller can react with the
 * matching UI: render {@link TextPreviewResult#content} in a dialog, or show visible
 * feedback that a download just started - the ticket's minimum requirement for every format that
 * does not get a preview, so a click never appears to do nothing.
 */
export type OpenDocumentContentResult =
  BlobPreviewResult | PdfPreviewResult | TextPreviewResult | DownloadResult

interface BlobPreviewResult {
  kind: 'blob-preview'
}

/** A PDF for the app's own viewer (PdfPreviewDialog), with where to jump in it. */
export interface PdfPreviewResult {
  kind: 'pdf-preview'
  fileName: string
  blob: Blob
  /** As {@link TextPreviewResult.anchor}: `null` is a citation without a stored anchor. */
  anchor?: ChunkAnchor | null
  /** SHA-256 of the original as indexed when the citation was made. */
  contentSha256?: string | null
}

export interface TextPreviewResult {
  kind: 'text-preview'
  fileName: string
  /** The essence (no `; charset=...` parameters) of the response's Content-Type, e.g.
   *  `text/markdown` or `text/plain` - decides whether the dialog renders Markdown or plain text. */
  contentType: string
  content: string
  /** The original as fetched, so the dialog's download hands out its exact bytes. */
  blob: Blob
  /** Where the cited passage sits - the preview scrolls there and marks it. `null` is a citation
   *  without a stored anchor and makes the preview say so; `undefined` means no citation at all. */
  anchor?: ChunkAnchor | null
}

export interface DownloadResult {
  kind: 'download'
  fileName: string
  /** Set when a Markdown/plain text original fell back to a download purely
   *  because it exceeded {@link TEXT_PREVIEW_MAX_BYTES} - the caller uses this to show "zu groß
   *  für die Vorschau" instead of the generic download message (see useDocumentPreview). */
  reason?: 'too-large-for-preview'
}

/**
 * Loads a document's original file as a Blob and either hands a PDF to the app's own viewer,
 * previews it in a new tab (PNG, JPEG, GIF and WebP only - see {@link PREVIEWABLE_CONTENT_TYPES}),
 * returns its text content for a
 * client-side preview dialog (Markdown/plain text), or downloads it under its original file
 * name (everything else - DOCX among them; there is no server-side conversion, but the download
 * itself must still be visible to the caller). `fallbackFileName` is used when the response
 * carries no Content-Disposition file name (should not normally happen, but the caller already
 * knows the name from its own document list).
 *
 * Which branch runs is decided purely by content type, never by `window.open`'s return value: with
 * `noopener` in its `windowFeatures`, `window.open` always returns `null` per spec regardless of
 * whether a tab actually opened - it cannot double as a popup-blocked signal here.
 *
 * The blob-preview/download branches' object URL is revoked after {@link OBJECT_URL_REVOKE_DELAY_MS}
 * rather than immediately - revoking it synchronously would race the new tab/download actually
 * reading the blob's bytes, especially for `window.open`, which only schedules navigation
 * asynchronously. A failure between creating the URL and scheduling that revoke (e.g.
 * `triggerDownload` throwing) must still revoke it - otherwise the blob leaks until the page
 * unloads. The text-preview branch never creates an object URL at all - `blob.text()` reads the
 * bytes directly, so there is nothing to revoke.
 */
export async function openDocumentContent(
  documentId: string,
  fallbackFileName: string,
): Promise<OpenDocumentContentResult> {
  const { blob, fileName } = await getDocumentContent(documentId)
  const resolvedFileName = fileName ?? fallbackFileName

  // A Markdown/plain text file above TEXT_PREVIEW_MAX_BYTES falls back to the
  // ordinary download branch below (with `reason: 'too-large-for-preview'`) rather than reading it
  // in full via `blob.text()`, which could freeze the tab on a large original.
  if (isTextPreviewable(blob.type) && !isTooLargeForTextPreview(blob.size)) {
    return {
      kind: 'text-preview',
      fileName: resolvedFileName,
      contentType: contentTypeEssence(blob.type),
      content: await blob.text(),
      blob,
    }
  }

  if (isPdf(blob.type)) {
    return { kind: 'pdf-preview', fileName: resolvedFileName, blob }
  }

  const objectUrl = URL.createObjectURL(blob)
  const previewable = isPreviewable(blob.type)
  try {
    if (previewable) {
      window.open(objectUrl, '_blank', 'noopener,noreferrer')
    } else {
      triggerDownload(objectUrl, resolvedFileName)
    }
  } catch (err) {
    URL.revokeObjectURL(objectUrl)
    throw err
  }

  setTimeout(() => URL.revokeObjectURL(objectUrl), OBJECT_URL_REVOKE_DELAY_MS)
  if (previewable) {
    return { kind: 'blob-preview' }
  }
  return {
    kind: 'download',
    fileName: resolvedFileName,
    reason: isTextPreviewable(blob.type) ? 'too-large-for-preview' : undefined,
  }
}

/**
 * Opens `blob` in the browser's own viewer in a new tab - the fallback when the app's PDF viewer
 * cannot show a file. A type outside {@link PREVIEWABLE_CONTENT_TYPES} is never opened. The object
 * URL is revoked after {@link OBJECT_URL_REVOKE_DELAY_MS}.
 */
export function openInBrowserViewer(blob: Blob) {
  if (!isPreviewable(blob.type)) {
    return
  }
  const objectUrl = URL.createObjectURL(blob)
  try {
    window.open(objectUrl, '_blank', 'noopener,noreferrer')
  } finally {
    setTimeout(() => URL.revokeObjectURL(objectUrl), OBJECT_URL_REVOKE_DELAY_MS)
  }
}

/** Saves `blob` under `fileName`; the object URL is revoked after {@link OBJECT_URL_REVOKE_DELAY_MS}. */
export function downloadBlob(blob: Blob, fileName: string) {
  const objectUrl = URL.createObjectURL(blob)
  try {
    triggerDownload(objectUrl, fileName)
  } finally {
    setTimeout(() => URL.revokeObjectURL(objectUrl), OBJECT_URL_REVOKE_DELAY_MS)
  }
}

/**
 * Fetches a document's original and saves it, whatever its type - the „Original herunterladen“
 * of a view that has not fetched the file itself. Returns the file name it was saved under.
 */
export async function downloadDocumentContent(
  documentId: string,
  fallbackFileName: string,
): Promise<string> {
  const { blob, fileName } = await getDocumentContent(documentId)
  const resolvedFileName = fileName ?? fallbackFileName
  downloadBlob(blob, resolvedFileName)
  return resolvedFileName
}
