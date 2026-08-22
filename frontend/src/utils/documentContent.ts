import { getDocumentContent } from '../services/api'

// #738/#739: shared between the library document list's "Original öffnen" action (#738) and the
// future citation deep link (#739) - both need to turn a documentId into a rendered/downloaded
// file, and neither can use a plain <a href> since the endpoint is Bearer-authenticated (ADR-0005).

// Content types the browser renders inline when navigated to directly - everything else falls back
// to a download instead of a probably-blank or broken preview tab. image/svg+xml is deliberately
// excluded even though it matches the image/ prefix: the object URL it would be opened from runs in
// this app's own origin, where the endpoint's response-level protections (CSP, X-Content-Type-Options
// - DocumentController) do not apply to a blob: URL, so an inline SVG would execute script in the
// app's context (#743 review).
const PREVIEWABLE_CONTENT_TYPE_PREFIXES = ['application/pdf', 'image/']
const NEVER_PREVIEWABLE_CONTENT_TYPES = ['image/svg+xml']

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
 * Loads a document's original file as a Blob and either previews it in a new tab (PDF/images, but
 * never SVG - see {@link NEVER_PREVIEWABLE_CONTENT_TYPES}) or downloads it under its original file
 * name (everything else). `fallbackFileName` is used when the response carries no
 * Content-Disposition file name (should not normally happen, but the caller already knows the name
 * from its own document list).
 *
 * Which branch runs is decided purely by content type, never by `window.open`'s return value: with
 * `noopener` in its `windowFeatures`, `window.open` always returns `null` per spec regardless of
 * whether a tab actually opened (#743 review) - it cannot double as a popup-blocked signal here.
 *
 * The created object URL is revoked after {@link OBJECT_URL_REVOKE_DELAY_MS} rather than
 * immediately - revoking it synchronously would race the new tab/download actually reading the
 * blob's bytes, especially for `window.open`, which only schedules navigation asynchronously. A
 * failure between creating the URL and scheduling that revoke (e.g. `triggerDownload` throwing)
 * must still revoke it - otherwise the blob leaks until the page unloads.
 */
export async function openDocumentContent(
  documentId: string,
  fallbackFileName: string,
): Promise<void> {
  const { blob, fileName } = await getDocumentContent(documentId)
  const objectUrl = URL.createObjectURL(blob)
  const resolvedFileName = fileName ?? fallbackFileName

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
}
