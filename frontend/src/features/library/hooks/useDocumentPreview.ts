import { useState } from 'react'
import { openDocumentContent } from '../utils/documentContent'
import type { PdfPreviewResult, TextPreviewResult } from '../utils/documentContent'
import type { ChunkAnchor } from '@/shared/api/types'
import { notify } from '@/shared/stores/notificationStore'
import { safeExternalHref } from '@/shared/utils/safeExternalHref'
import type { SourceTypeKey } from '@/shared/api/types'
import { sourceRegistration } from '../components/sources/registry'
import { withTextFragment } from '../utils/textFragment'

/**
 * Everything "Original öffnen"/"Dokument öffnen" needs to decide HOW to open a document -
 * a Confluence document opens at its source URL, every other type through the content endpoint.
 */
export interface OpenableDocument {
  id: string
  fileName: string
  sourceType?: SourceTypeKey | null
  sourceUrl?: string | null
  sourceEntryUrl?: string | null
  /** Where the cited passage sits - a PDF, Markdown or text file opens there with the passage
   *  marked, a page at the source through a text fragment. */
  anchor?: ChunkAnchor | null
  /** SHA-256 of the original as indexed when the citation was made. */
  contentSha256?: string | null
}

/**
 * The shared "Dokument öffnen"/"Original öffnen" click handling behind
 * {@link ../components/DocumentTextPreviewDialog} - LibraryDetailPage and the chat's
 * source views share the outcome handling (open a preview dialog, notify about a
 * download, surface a fetch failure) here rather than duplicating it per caller. Download
 * notices and failures surface as global popup notifications (guidelines 5.9), never as inline
 * alerts near the triggering element.
 */
export function useDocumentPreview() {
  const [previewDocument, setPreviewDocument] = useState<TextPreviewResult | null>(null)
  const [pdfPreview, setPdfPreview] = useState<PdfPreviewResult | null>(null)

  async function openDocument(document: OpenableDocument) {
    // ADR-0023: a source whose original is the page at the source (Confluence) has no file of its
    // own - the backend's content endpoint deliberately answers 404 there. It opens directly at
    // the source URL, exactly like the citation deep link.
    if (sourceRegistration(document.sourceType)?.opensAtSource) {
      // Only an http(s) address is opened; any other scheme counts as no address at all.
      const target = safeExternalHref(document.sourceUrl ?? document.sourceEntryUrl)
      if (!target) {
        notify('Für dieses Dokument ist keine Quell-Adresse hinterlegt.', 'error')
        return
      }
      window.open(withTextFragment(target, document.anchor), '_blank', 'noopener,noreferrer')
      return
    }
    try {
      const result = await openDocumentContent(document.id, document.fileName)
      if (result.kind === 'text-preview') {
        setPreviewDocument({ ...result, anchor: document.anchor })
      } else if (result.kind === 'pdf-preview') {
        setPdfPreview({
          ...result,
          anchor: document.anchor,
          contentSha256: document.contentSha256,
        })
      } else if (result.kind === 'download') {
        // Every format without a preview (DOCX among them) must give visible feedback - a click
        // that only starts a silent download otherwise looks like it did nothing. A Markdown/plain
        // text original that only fell back to
        // a download because it exceeded the size cap gets its own, more informative message.
        notify(
          result.reason === 'too-large-for-preview'
            ? `${result.fileName} ist zu groß für die Vorschau – wird heruntergeladen`
            : `${result.fileName} wird heruntergeladen`,
          'info',
        )
      }
    } catch (err) {
      notify(
        err instanceof Error ? err.message : 'Das Original konnte nicht geöffnet werden.',
        'error',
      )
    }
  }

  return {
    previewDocument,
    closePreview: () => setPreviewDocument(null),
    pdfPreview,
    closePdfPreview: () => setPdfPreview(null),
    openDocument,
  }
}
