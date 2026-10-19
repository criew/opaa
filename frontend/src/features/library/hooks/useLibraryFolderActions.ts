import { useState } from 'react'
import type { LibraryFolderListItem } from '@/shared/api/types'
import { getLibraryFolder } from '../api/libraryApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { useDocumentStore } from '../stores/documentStore'

/**
 * Folder management of an UPLOAD library: which folder dialog or menu is open, and creating,
 * renaming and deleting a folder. A failed create or rename rejects, so the open dialog can show
 * the conflict; a failed delete surfaces via documentStore.folderError.
 */
export function useLibraryFolderActions(
  libraryId: string,
  folderIdParam: string | null,
  onDocumentsChanged: () => void,
) {
  const createFolder = useDocumentStore((s) => s.createFolder)
  const renameFolder = useDocumentStore((s) => s.renameFolder)
  const removeFolder = useDocumentStore((s) => s.removeFolder)

  const [newFolderDialogOpen, setNewFolderDialogOpen] = useState(false)
  const [renameFolderTarget, setRenameFolderTarget] = useState<LibraryFolderListItem | null>(null)
  const [folderMenu, setFolderMenu] = useState<{
    anchorEl: HTMLElement
    folder: LibraryFolderListItem
  } | null>(null)

  async function handleCreateFolder(name: string) {
    // Derives the parent explicitly from the URL's own folder param, so a
    // click before the deep-linked folder's first load has resolved cannot create it at the root.
    await createFolder(libraryId, name, folderIdParam)
  }

  async function handleRenameFolder(folder: LibraryFolderListItem, name: string) {
    await renameFolder(libraryId, folder.id, name)
  }

  async function handleDeleteFolder(folder: LibraryFolderListItem) {
    // re-fetches the folder right before confirming, so the confirmation
    // names the current recursive document count rather than a possibly stale one from the list.
    let documentCount = folder.documentCount
    try {
      const current = await getLibraryFolder(libraryId, folder.id)
      documentCount = current.documentCount
    } catch {
      // Falls back to the (possibly stale) count already shown in the row above.
    }
    const question =
      documentCount > 0
        ? `Ordner "${folder.name}" und ${documentCount} ${
            documentCount === 1 ? 'Dokument' : 'Dokumente'
          } löschen?`
        : `Ordner "${folder.name}" löschen?`
    const confirmed = await confirmAction({
      question,
      consequence: 'Diese Aktion kann nicht rückgängig gemacht werden.',
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    try {
      await removeFolder(libraryId, folder.id)
      onDocumentsChanged()
    } catch {
      // The error message is already shown via documentStore.folderError.
    }
  }

  return {
    newFolderDialogOpen,
    setNewFolderDialogOpen,
    renameFolderTarget,
    setRenameFolderTarget,
    folderMenu,
    setFolderMenu,
    handleCreateFolder,
    handleRenameFolder,
    handleDeleteFolder,
  }
}

export type LibraryFolderActions = ReturnType<typeof useLibraryFolderActions>
