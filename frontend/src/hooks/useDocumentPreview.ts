import { useState } from 'react'
import { openDocumentContent } from '../utils/documentContent'
import type { TextPreviewResult } from '../utils/documentContent'

/**
 * #780: the shared "Im Dokument öffnen"/"Original öffnen" click handling behind
 * {@link ../components/DocumentTextPreviewDialog} and each download's visible feedback -
 * LibraryDetailPage (#738) and SourceEvidenceDrawer (#739) both fetch a document's original file
 * through {@link openDocumentContent} and only differ in which button triggers it, so this hook
 * carries the outcome handling (open a preview dialog, show a download notice, surface a fetch
 * failure) once rather than duplicated per caller.
 */
export function useDocumentPreview() {
  const [error, setError] = useState<string | null>(null)
  const [previewDocument, setPreviewDocument] = useState<TextPreviewResult | null>(null)
  const [downloadMessage, setDownloadMessage] = useState<string | null>(null)

  async function openDocument(documentId: string, fallbackFileName: string) {
    setError(null)
    try {
      const result = await openDocumentContent(documentId, fallbackFileName)
      if (result.kind === 'text-preview') {
        setPreviewDocument(result)
      } else if (result.kind === 'download') {
        // #780 acceptance criteria: every format without a preview (DOCX among them) must give
        // visible feedback - a click that only starts a silent download otherwise looks like it did
        // nothing.
        setDownloadMessage(`${result.fileName} wird heruntergeladen`)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Das Original konnte nicht geöffnet werden.')
    }
  }

  return {
    error,
    clearError: () => setError(null),
    previewDocument,
    closePreview: () => setPreviewDocument(null),
    downloadMessage,
    clearDownloadMessage: () => setDownloadMessage(null),
    openDocument,
  }
}
