import { useRef, useState } from 'react'
import { openDocumentContent, opensAsTextVersion } from '../utils/documentContent'
import type {
  PdfPreviewResult,
  TextPreviewResult,
  TextVersionPreview,
} from '../utils/documentContent'
import type { ChunkAnchor } from '@/shared/api/types'
import { notify } from '@/shared/stores/notificationStore'
import { safeExternalHref } from '@/shared/utils/safeExternalHref'
import type { SourceTypeKey } from '@/shared/api/types'
import { sourceRegistration } from '../components/sources/registry'
import { withTextFragment } from '../utils/textFragment'
import {
  loadOfficeOriginal,
  originalFormat,
  type OfficeOriginal,
  type OriginalFallback,
} from '../utils/officeOriginal'

/** What an original view shows while its file is fetched: the name and, for the card, the anchor. */
export interface PendingOriginal {
  fileName: string
  anchor?: ChunkAnchor | null
}

const FALLBACK_NOTICES: Record<OriginalFallback | 'unreadable', (fileName: string) => string> = {
  'too-large': (fileName) =>
    `${fileName} ist zu groß für die Originalansicht – die Textfassung wird gezeigt.`,
  'no-plain-text': (fileName) => `${fileName} hat keinen Textteil – die Textfassung wird gezeigt.`,
  failed: (fileName) =>
    `Das Original von ${fileName} konnte nicht geladen werden – die Textfassung wird gezeigt.`,
  unreadable: (fileName) =>
    `${fileName} lässt sich nicht als Original darstellen – die Textfassung wird gezeigt.`,
}

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
   *  marked, a page at the source through a text fragment. `null` for a citation whose anchor was
   *  never stored (indexed before anchors existed); the preview then says so. */
  anchor?: ChunkAnchor | null
  /** SHA-256 of the original as indexed when the citation was made. */
  contentSha256?: string | null
  /** The cited passage - a Textfassung opens there and marks it. */
  chunkIndex?: number | null
}

/** Whether a document of `sourceType` has a file of its own; a page at the source has none. */
export function hasOwnOriginal(sourceType: SourceTypeKey | null | undefined): boolean {
  return !sourceRegistration(sourceType)?.opensAtSource
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
  const [textVersion, setTextVersion] = useState<TextVersionPreview | null>(null)
  const [officeOriginal, setOfficeOriginal] = useState<OfficeOriginal | null>(null)
  const [pendingOriginal, setPendingOriginal] = useState<PendingOriginal | null>(null)
  // counts the original fetches; one that is closed or overtaken is not shown when it arrives
  const originalRequest = useRef(0)
  const originalAbort = useRef<AbortController | null>(null)

  function openTextVersion(document: OpenableDocument) {
    setTextVersion({
      documentId: document.id,
      fileName: document.fileName,
      chunkIndex: document.chunkIndex,
      anchor: document.anchor,
    })
  }

  async function openDocument(document: OpenableDocument) {
    // ADR-0023: a source whose original is the page at the source (Confluence) has no file of its
    // own - the backend's content endpoint deliberately answers 404 there. It opens directly at
    // the source URL, exactly like the citation deep link.
    if (!hasOwnOriginal(document.sourceType)) {
      // Only an http(s) address is opened; any other scheme counts as no address at all.
      const target = safeExternalHref(document.sourceUrl ?? document.sourceEntryUrl)
      if (!target) {
        notify('Für dieses Dokument ist keine Quell-Adresse hinterlegt.', 'error')
        return
      }
      window.open(withTextFragment(target, document.anchor), '_blank', 'noopener,noreferrer')
      return
    }
    // Word, CSV and mail open as themselves, the view loading while the file is fetched; one too
    // large to show, a mail without plain text or an original that cannot be fetched opens as its
    // Textfassung, which the index still holds, and a notice says so
    const format = originalFormat(document.fileName)
    if (format) {
      const request = ++originalRequest.current
      originalAbort.current?.abort()
      const abort = new AbortController()
      originalAbort.current = abort
      setPendingOriginal({ fileName: document.fileName, anchor: document.anchor })
      const outcome = await loadOfficeOriginal(
        format,
        {
          documentId: document.id,
          fileName: document.fileName,
          anchor: document.anchor,
          chunkIndex: document.chunkIndex,
        },
        abort.signal,
      ).catch(() => ({ fallback: 'failed' as const }))
      if (request !== originalRequest.current) return
      setPendingOriginal(null)
      if ('fallback' in outcome) {
        notify(FALLBACK_NOTICES[outcome.fallback](document.fileName), 'info')
        openTextVersion(document)
      } else {
        setOfficeOriginal(outcome)
      }
      return
    }
    // a format the browser cannot show opens as its Textfassung; the original is not fetched
    if (opensAsTextVersion(document.fileName)) {
      openTextVersion(document)
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
          documentId: document.id,
          chunkIndex: document.chunkIndex,
        })
      } else if (result.kind === 'download') {
        // Every format without a preview must give visible feedback - a click
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
    textVersion,
    closeTextVersion: () => setTextVersion(null),
    officeOriginal,
    /** The original whose file is being fetched; its view shows it loading. */
    pendingOriginal,
    closeOfficeOriginal: () => {
      originalRequest.current++
      originalAbort.current?.abort()
      setPendingOriginal(null)
      setOfficeOriginal(null)
    },
    /** The original view could not render its file: the Textfassung takes its place. */
    replaceOriginalByTextVersion: (original: OfficeOriginal) => {
      notify(FALLBACK_NOTICES.unreadable(original.fileName), 'info')
      setOfficeOriginal(null)
      openTextVersion({
        id: original.documentId,
        fileName: original.fileName,
        anchor: original.anchor,
        chunkIndex: original.chunkIndex,
      })
    },
    openDocument,
  }
}
