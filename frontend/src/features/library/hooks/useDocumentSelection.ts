import { useState } from 'react'
import type { LibraryDocumentResponse } from '@/shared/api/types'

/**
 * The selection of the document list. "Alle auswählen" acts on the documents currently shown
 * only; ids selected elsewhere stay as they are.
 */
export function useDocumentSelection(documents: LibraryDocumentResponse[]) {
  const [selectedDocumentIds, setSelectedDocumentIds] = useState<string[]>([])

  function toggleSelected(documentId: string) {
    setSelectedDocumentIds((previous) =>
      previous.includes(documentId)
        ? previous.filter((id) => id !== documentId)
        : [...previous, documentId],
    )
  }

  const visibleDocumentIds = documents.map((doc) => doc.id)
  const allVisibleSelected =
    visibleDocumentIds.length > 0 &&
    visibleDocumentIds.every((id) => selectedDocumentIds.includes(id))

  function toggleAllVisible() {
    setSelectedDocumentIds((previous) =>
      allVisibleSelected
        ? previous.filter((id) => !visibleDocumentIds.includes(id))
        : [...previous, ...visibleDocumentIds.filter((id) => !previous.includes(id))],
    )
  }

  return { selectedDocumentIds, setSelectedDocumentIds, toggleSelected, toggleAllVisible }
}
