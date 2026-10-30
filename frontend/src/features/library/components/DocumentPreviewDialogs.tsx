import type { useDocumentPreview } from '../hooks/useDocumentPreview'
import DocumentTextPreviewDialog from './DocumentTextPreviewDialog'
import DocumentTextVersionDialog from './DocumentTextVersionDialog'
import PdfPreviewDialog from './PdfPreviewDialog'

interface DocumentPreviewDialogsProps {
  /** The `useDocumentPreview()` instance whose „Dokument öffnen“ these dialogs show. */
  preview: ReturnType<typeof useDocumentPreview>
}

/** Every view „Dokument öffnen“ can end in; at most one of them is open. */
export default function DocumentPreviewDialogs({ preview }: DocumentPreviewDialogsProps) {
  return (
    <>
      <DocumentTextPreviewDialog
        previewDocument={preview.previewDocument}
        onClose={preview.closePreview}
      />
      <PdfPreviewDialog preview={preview.pdfPreview} onClose={preview.closePdfPreview} />
      <DocumentTextVersionDialog preview={preview.textVersion} onClose={preview.closeTextVersion} />
    </>
  )
}
