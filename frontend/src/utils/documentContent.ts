import { getDocumentContent } from '../services/api'

// #738/#739: shared between the library document list's "Original öffnen" action (#738) and the
// future citation deep link (#739) - both need to turn a documentId into a rendered/downloaded
// file, and neither can use a plain <a href> since the endpoint is Bearer-authenticated (ADR-0005).

// Content types the browser renders inline when navigated to directly - everything else falls back
// to a download instead of a probably-blank or broken preview tab.
const PREVIEWABLE_CONTENT_TYPE_PREFIXES = ['application/pdf', 'image/']

function isPreviewable(contentType: string): boolean {
  return PREVIEWABLE_CONTENT_TYPE_PREFIXES.some((prefix) => contentType.startsWith(prefix))
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
 * Loads a document's original file as a Blob and either previews it in a new tab (PDF/images) or
 * downloads it under its original file name (everything else). `fallbackFileName` is used when the
 * response carries no Content-Disposition file name (should not normally happen, but the caller
 * already knows the name from its own document list).
 *
 * The created object URL is revoked after {@link OBJECT_URL_REVOKE_DELAY_MS} rather than
 * immediately - revoking it synchronously would race the new tab/download actually reading the
 * blob's bytes, especially for `window.open`, which only schedules navigation asynchronously.
 */
export async function openDocumentContent(
  documentId: string,
  fallbackFileName: string,
): Promise<void> {
  const { blob, fileName } = await getDocumentContent(documentId)
  const objectUrl = URL.createObjectURL(blob)
  const resolvedFileName = fileName ?? fallbackFileName

  if (isPreviewable(blob.type)) {
    const opened = window.open(objectUrl, '_blank', 'noopener,noreferrer')
    if (!opened) {
      // Popup blocked (or jsdom in tests, which never opens a real tab) - fall back to a download
      // rather than silently doing nothing.
      triggerDownload(objectUrl, resolvedFileName)
    }
  } else {
    triggerDownload(objectUrl, resolvedFileName)
  }

  setTimeout(() => URL.revokeObjectURL(objectUrl), OBJECT_URL_REVOKE_DELAY_MS)
}

/**
 * Opens an external source URL (HTTP_DIRECTORY's own location, or the RSS entry page an
 * attachment was found on) in a new tab - the remote-source counterpart to
 * {@link openDocumentContent} for a document with no local file, sharing the same
 * noopener/noreferrer new-tab behaviour.
 */
export function openExternalSourceUrl(url: string): void {
  window.open(url, '_blank', 'noopener,noreferrer')
}
