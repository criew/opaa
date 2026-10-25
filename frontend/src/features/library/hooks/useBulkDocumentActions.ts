import { useState } from 'react'
import type { Dispatch, SetStateAction } from 'react'
import type { BulkMetadataValueResponse } from '@/shared/api/types'
import { confirmAction } from '@/shared/stores/confirmStore'
import { useDocumentStore } from '../stores/documentStore'

/** The most documents one bulk delete may name - mirrors BulkDocumentDeleteRequest's own ceiling. */
export const MAX_BULK_DELETE = 200

interface BulkDocumentActionsOptions {
  libraryId: string
  selectedDocumentIds: string[]
  setSelectedDocumentIds: Dispatch<SetStateAction<string[]>>
  setMetadataRefreshToken: Dispatch<SetStateAction<number>>
  onDocumentsChanged: () => void
}

/**
 * The Sammelaktionen on the selected documents - "Feld setzen" through BulkMetadataDialog and the
 * Sammellöschen - and the outcome of the last one. A finished action empties the selection.
 */
export function useBulkDocumentActions({
  libraryId,
  selectedDocumentIds,
  setSelectedDocumentIds,
  setMetadataRefreshToken,
  onDocumentsChanged,
}: BulkDocumentActionsOptions) {
  const loadDocuments = useDocumentStore((s) => s.loadDocuments)
  const removeDocuments = useDocumentStore((s) => s.removeDocuments)

  const [bulkDialogOpen, setBulkDialogOpen] = useState(false)
  // The outcome of the last Sammelaktion. Carries its own severity: a delete that left documents
  // standing is a warning, not the green success a metadata assignment produces.
  const [bulkResult, setBulkResult] = useState<{
    severity: 'success' | 'warning'
    text: string
  } | null>(null)
  const tooManySelectedToDelete = selectedDocumentIds.length > MAX_BULK_DELETE

  function handleBulkDone(result: BulkMetadataValueResponse) {
    const parts = [
      `${result.updatedCount} ${result.updatedCount === 1 ? 'Dokument' : 'Dokumente'} aktualisiert`,
      `${result.unchangedCount} unverändert`,
    ]
    if (result.rejectedDocumentIds.length > 0) {
      parts.push(`${result.rejectedDocumentIds.length} abgewiesen`)
    }
    setBulkResult({ severity: 'success', text: `Feld gesetzt: ${parts.join(', ')}.` })
    setSelectedDocumentIds([])
    setMetadataRefreshToken((previous) => previous + 1)
    // A document that just got a value leaves the anchor's worklist - reload the same page.
    void loadDocuments(libraryId, {})
  }

  /**
   * The Sammellöschen of an upload library. The confirmation names the number, and the
   * answer is reported as it comes back - a partial success says how many stayed and why, instead
   * of leaving the person to compare the list against their own selection. Identical reasons are
   * counted rather than repeated, so fifty stale ids read as one sentence.
   */
  async function handleBulkDelete() {
    const ids = selectedDocumentIds
    // The ceiling is announced in the toolbar and disables the button there; this is the guard
    // that keeps a racing click from sending an over-long request the backend would reject.
    if (ids.length === 0 || ids.length > MAX_BULK_DELETE) return
    const confirmed = await confirmAction({
      question:
        ids.length === 1 ? '1 Dokument löschen?' : `${ids.length} ausgewählte Dokumente löschen?`,
      consequence:
        'Die Dokumente werden mit ihren Anhängen aus dieser Bibliothek entfernt. Diese Aktion kann nicht rückgängig gemacht werden.',
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    try {
      const result = await removeDocuments(libraryId, ids)
      if (!result) return
      const deleted = result.deletedDocumentIds.length
      if (result.failures.length === 0) {
        setBulkResult({
          severity: 'success',
          text: `${deleted} ${deleted === 1 ? 'Dokument' : 'Dokumente'} gelöscht.`,
        })
      } else {
        const countByReason = new Map<string, number>()
        for (const failure of result.failures) {
          countByReason.set(failure.message, (countByReason.get(failure.message) ?? 0) + 1)
        }
        const reasons = [...countByReason.entries()].map(([reason, count]) =>
          count === 1 ? `1 Dokument: ${reason}` : `${count} Dokumente: ${reason}`,
        )
        setBulkResult({
          severity: 'warning',
          text: `${deleted} von ${ids.length} Dokumenten gelöscht. Nicht gelöscht — ${reasons.join('; ')}.`,
        })
      }
      setSelectedDocumentIds([])
      onDocumentsChanged()
    } catch {
      // The error message is already shown via documentStore.deleteError.
    }
  }

  return {
    bulkDialogOpen,
    setBulkDialogOpen,
    bulkResult,
    setBulkResult,
    tooManySelectedToDelete,
    handleBulkDone,
    handleBulkDelete,
  }
}

export type BulkDocumentActions = ReturnType<typeof useBulkDocumentActions>
