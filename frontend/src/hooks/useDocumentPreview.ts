import { useState } from 'react'
import { openDocumentContent } from '../utils/documentContent'
import type { TextPreviewResult } from '../utils/documentContent'
import { notify } from '../stores/notificationStore'
import type { SourceTypeKey } from '../types/api'
import { sourceRegistration } from '../components/library/sources/registry'

/**
 * Everything "Original öffnen"/"Im Dokument öffnen" needs to decide HOW to open a document -
 * a Confluence document opens at its source URL, every other type through the content endpoint.
 */
export interface OpenableDocument {
  id: string
  fileName: string
  sourceType?: SourceTypeKey | null
  sourceUrl?: string | null
  sourceEntryUrl?: string | null
}

/**
 * #780: the shared "Im Dokument öffnen"/"Original öffnen" click handling behind
 * {@link ../components/DocumentTextPreviewDialog} - LibraryDetailPage (#738) and the chat's
 * source views (#739) share the outcome handling (open a preview dialog, notify about a
 * download, surface a fetch failure) here rather than duplicating it per caller. Download
 * notices and failures surface as global popup notifications (guidelines 5.9), never as inline
 * alerts near the triggering element.
 */
export function useDocumentPreview() {
  const [previewDocument, setPreviewDocument] = useState<TextPreviewResult | null>(null)

  async function openDocument(document: OpenableDocument) {
    // ADR-0023: a source whose original is the page at the source (Confluence) has no file of its
    // own - the backend's content endpoint deliberately answers 404 there. It opens directly at
    // the source URL, exactly like the citation deep link.
    if (sourceRegistration(document.sourceType)?.opensAtSource) {
      const target = document.sourceUrl ?? document.sourceEntryUrl
      if (!target) {
        notify('Für dieses Dokument ist keine Quell-Adresse hinterlegt.', 'error')
        return
      }
      window.open(target, '_blank', 'noopener,noreferrer')
      return
    }
    try {
      const result = await openDocumentContent(document.id, document.fileName)
      if (result.kind === 'text-preview') {
        setPreviewDocument(result)
      } else if (result.kind === 'download') {
        // #780 acceptance criteria: every format without a preview (DOCX among them) must give
        // visible feedback - a click that only starts a silent download otherwise looks like it
        // did nothing. #781 review, Nit 3: a Markdown/plain text original that only fell back to
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
    openDocument,
  }
}
