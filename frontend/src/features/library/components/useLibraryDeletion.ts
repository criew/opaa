import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import type { LibraryListResponse, LibraryResponse } from '@/shared/api/types'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { CATALOG_ROUTE } from '@/shared/routes'
import { useLibraryStore } from '../stores/libraryStore'
import { PRIVATE_LIBRARY_ERASURE_CONSEQUENCE } from './privateLibrary'

interface LibraryDeletionOptions {
  libraryId: string | undefined
  library: LibraryResponse | LibraryListResponse | undefined
  details: LibraryResponse | undefined
  /** The library is marked for erasure. */
  erasing: boolean
  setLocalError: (message: string | null) => void
}

/**
 * Deleting a library from its detail page: „Löschen", and „Sofort löschen" of a private library
 * with the notice that takes the focus once the erasure is only marked.
 */
export function useLibraryDeletion({
  libraryId,
  library,
  details,
  erasing,
  setLocalError,
}: LibraryDeletionOptions) {
  const navigate = useNavigate()
  const deleteExistingLibrary = useLibraryStore((s) => s.deleteExistingLibrary)
  const erasureNoticeRef = useRef<HTMLDivElement>(null)
  // Set by a 202: the menu that held „Sofort löschen" is gone, so the focus moves to the notice.
  const focusErasureNoticeRef = useRef(false)
  const [eraseBusy, setEraseBusy] = useState(false)

  useEffect(() => {
    if (focusErasureNoticeRef.current && erasing) {
      focusErasureNoticeRef.current = false
      erasureNoticeRef.current?.focus()
    }
  }, [erasing])

  /**
   * „Sofort löschen" of a private library: 204 erased it, 202 marked it while a run still ends -
   * the page then stays and shows the state, without promising when the erasure completes.
   */
  async function handleErase() {
    if (!libraryId || !library || eraseBusy) return
    const confirmed = await confirmAction({
      question: `„${library.name}“ sofort löschen?`,
      consequence: PRIVATE_LIBRARY_ERASURE_CONSEQUENCE,
      confirmLabel: 'Endgültig löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    setLocalError(null)
    setEraseBusy(true)
    // armed before the request: the store reloads the marked library before it answers
    focusErasureNoticeRef.current = true
    try {
      const outcome = await deleteExistingLibrary(libraryId)
      if (outcome === 'ERASURE_PENDING') {
        // also when reloading the marked library failed and the page cannot show its state
        notify(
          `„${library.name}“ ist zur Löschung vorgemerkt. Die Löschung schließt ab, sobald die laufende Indexierung beendet ist.`,
          'info',
        )
        return
      }
      focusErasureNoticeRef.current = false
      notify(`„${library.name}“ ist gelöscht.`, 'success')
      navigate(CATALOG_ROUTE)
    } catch (err) {
      focusErasureNoticeRef.current = false
      setLocalError(err instanceof Error ? err.message : 'Löschen fehlgeschlagen')
    } finally {
      setEraseBusy(false)
    }
  }

  async function handleDelete() {
    if (!libraryId || !library) return
    // ADR-0018, Entscheidung 5: deleting a connector library also removes its
    // entire indexed content - a stronger effect than for an UPLOAD library, whose
    // deletion stays blocked as long as it still contains documents.
    const isConnectorLibrary = details != null && details.sourceType !== 'UPLOAD'
    const confirmed = await confirmAction({
      question: `Bibliothek "${library.name}" löschen?`,
      consequence: isConnectorLibrary
        ? 'Das entfernt auch alle indizierten Dokumente dieser Bibliothek. Diese Aktion kann nicht rückgängig gemacht werden.'
        : 'Diese Aktion kann nicht rückgängig gemacht werden.',
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    setLocalError(null)
    try {
      await deleteExistingLibrary(libraryId)
      navigate(CATALOG_ROUTE)
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : 'Löschen fehlgeschlagen')
    }
  }

  return { erasureNoticeRef, eraseBusy, handleErase, handleDelete }
}
