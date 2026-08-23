import { getDocumentContent } from '../services/api'

// #738/#739/#780: shared between the library document list's "Original öffnen" action (#738), the
// citation deep link (#739) and the Belegfenster's "Im Dokument öffnen" action - all three need to
// turn a documentId into a rendered/downloaded/previewed file, and neither can use a plain
// <a href> since the endpoint is Bearer-authenticated (ADR-0005).

// Content types the browser renders inline when navigated to directly - everything else falls back
// to a text preview or a download instead of a probably-blank or broken preview tab. image/svg+xml is
// deliberately excluded even though it matches the image/ prefix: the object URL it would be opened
// from runs in this app's own origin, where the endpoint's response-level protections (CSP,
// X-Content-Type-Options - DocumentController) do not apply to a blob: URL, so an inline SVG would
// execute script in the app's context (#743 review).
const PREVIEWABLE_CONTENT_TYPE_PREFIXES = ['application/pdf', 'image/']
const NEVER_PREVIEWABLE_CONTENT_TYPES = ['image/svg+xml']

// #780: Markdown and plain text cannot be opened as a blob: URL like a PDF - navigated to directly, a
// text/markdown or text/plain blob is either shown as raw, unrendered text or downloaded outright
// depending on the browser, neither of which is the readable preview the ticket asks for. Both are
// instead rendered client-side in DocumentTextPreviewDialog (../components/DocumentTextPreviewDialog)
// via MarkdownRenderer - which never passes raw HTML through (no rehype-raw plugin) and sanitizes
// link/image URLs against a safe-protocol allowlist (react-markdown's defaultUrlTransform strips
// e.g. javascript: - see documentContent.test.ts and DocumentTextPreviewDialog.test.tsx), so no
// gerendertes Markdown can execute script in this app's origin (#780 acceptance criteria, mirrors the
// #743 SVG Sperre above for a different attack surface).
const TEXT_PREVIEWABLE_CONTENT_TYPES = ['text/markdown', 'text/plain']

// #748 review, finding 2b: `blob.type` carries the full Content-Type line, parameters included
// (e.g. `image/svg+xml; charset=utf-8`) - comparing that verbatim against
// NEVER_PREVIEWABLE_CONTENT_TYPES let a source that adds a harmless-looking parameter slip past the
// exact-match sperre while still satisfying the `startsWith('image/')` prefix check below, exactly
// the SVG-in-this-origin scenario #743 exists to close. The essence (type/subtype, no parameters) is
// what both checks below actually mean to compare.
function contentTypeEssence(contentType: string): string {
  return contentType.split(';')[0].trim().toLowerCase()
}

function isPreviewable(contentType: string): boolean {
  const essence = contentTypeEssence(contentType)
  if (NEVER_PREVIEWABLE_CONTENT_TYPES.includes(essence)) {
    return false
  }
  return PREVIEWABLE_CONTENT_TYPE_PREFIXES.some((prefix) => essence.startsWith(prefix))
}

function isTextPreviewable(contentType: string): boolean {
  return TEXT_PREVIEWABLE_CONTENT_TYPES.includes(contentTypeEssence(contentType))
}

// Exported so tests can advance fake timers by exactly this amount instead of a magic number, and
// so a future caller with different lifetime needs (e.g. a longer-lived preview) can reason about
// it explicitly rather than guessing at the delay from behaviour alone.
export const OBJECT_URL_REVOKE_DELAY_MS = 60_000

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
 * matching UI (#780): render {@link TextPreviewResult#content} in a dialog, or show visible
 * feedback that a download just started - the ticket's minimum requirement for every format that
 * does not get a preview, so a click never appears to do nothing.
 */
export type OpenDocumentContentResult = BlobPreviewResult | TextPreviewResult | DownloadResult

interface BlobPreviewResult {
  kind: 'blob-preview'
}

export interface TextPreviewResult {
  kind: 'text-preview'
  fileName: string
  /** The essence (no `; charset=...` parameters) of the response's Content-Type, e.g.
   *  `text/markdown` or `text/plain` - decides whether the dialog renders Markdown or plain text. */
  contentType: string
  content: string
}

export interface DownloadResult {
  kind: 'download'
  fileName: string
}

/**
 * Loads a document's original file as a Blob and either previews it in a new tab (PDF/images, but
 * never SVG - see {@link NEVER_PREVIEWABLE_CONTENT_TYPES}), returns its text content for a
 * client-side preview dialog (Markdown/plain text, #780), or downloads it under its original file
 * name (everything else - DOCX among them, #780: converting it server-side is out of scope, but the
 * download itself must still be visible to the caller). `fallbackFileName` is used when the response
 * carries no Content-Disposition file name (should not normally happen, but the caller already
 * knows the name from its own document list).
 *
 * Which branch runs is decided purely by content type, never by `window.open`'s return value: with
 * `noopener` in its `windowFeatures`, `window.open` always returns `null` per spec regardless of
 * whether a tab actually opened (#743 review) - it cannot double as a popup-blocked signal here.
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

  if (isTextPreviewable(blob.type)) {
    return {
      kind: 'text-preview',
      fileName: resolvedFileName,
      contentType: contentTypeEssence(blob.type),
      content: await blob.text(),
    }
  }

  const objectUrl = URL.createObjectURL(blob)
  try {
    if (isPreviewable(blob.type)) {
      window.open(objectUrl, '_blank', 'noopener,noreferrer')
    } else {
      triggerDownload(objectUrl, resolvedFileName)
    }
  } catch (err) {
    URL.revokeObjectURL(objectUrl)
    throw err
  }

  setTimeout(() => URL.revokeObjectURL(objectUrl), OBJECT_URL_REVOKE_DELAY_MS)
  return isPreviewable(blob.type)
    ? { kind: 'blob-preview' }
    : { kind: 'download', fileName: resolvedFileName }
}
