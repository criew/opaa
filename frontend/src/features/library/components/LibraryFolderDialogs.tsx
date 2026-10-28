import { useDocumentStore } from '../stores/documentStore'
import type { LibraryFolderActions } from '../hooks/useLibraryFolderActions'
import NewFolderDialog from './NewFolderDialog'
import RenameFolderDialog from './RenameFolderDialog'

interface LibraryFolderDialogsProps {
  folderActions: LibraryFolderActions
}

/**
 * The two folder dialogs. Closing a dialog clears documentStore.folderError, which the open
 * dialog showed locally.
 */
export default function LibraryFolderDialogs({ folderActions }: LibraryFolderDialogsProps) {
  const clearFolderError = useDocumentStore((s) => s.clearFolderError)
  const {
    newFolderDialogOpen,
    setNewFolderDialogOpen,
    renameFolderTarget,
    setRenameFolderTarget,
    handleCreateFolder,
    handleRenameFolder,
  } = folderActions

  return (
    <>
      <NewFolderDialog
        // Remount on open, so the dialog's internal field state always starts fresh.
        key={newFolderDialogOpen ? 'new-folder-open' : 'new-folder-closed'}
        open={newFolderDialogOpen}
        onClose={() => {
          setNewFolderDialogOpen(false)
          // Without this, cancelling out of a dialog that just showed a
          // 409 conflict left documentStore.folderError set, flashing it on the page behind.
          clearFolderError()
        }}
        onCreate={handleCreateFolder}
      />

      <RenameFolderDialog
        key={renameFolderTarget ? `rename-${renameFolderTarget.id}-open` : 'rename-closed'}
        open={renameFolderTarget != null}
        folder={renameFolderTarget}
        onClose={() => {
          setRenameFolderTarget(null)
          clearFolderError()
        }}
        onRename={(name) =>
          renameFolderTarget ? handleRenameFolder(renameFolderTarget, name) : Promise.resolve()
        }
      />
    </>
  )
}
