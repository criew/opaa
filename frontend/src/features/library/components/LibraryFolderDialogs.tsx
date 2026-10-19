import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import { useDocumentStore } from '../stores/documentStore'
import type { LibraryFolderActions } from '../hooks/useLibraryFolderActions'
import NewFolderDialog from './NewFolderDialog'
import RenameFolderDialog from './RenameFolderDialog'

interface LibraryFolderDialogsProps {
  folderActions: LibraryFolderActions
}

/**
 * The folder menu of a row ("Umbenennen", "Löschen") and the two folder dialogs. Closing a dialog
 * clears documentStore.folderError, which the open dialog showed locally.
 */
export default function LibraryFolderDialogs({ folderActions }: LibraryFolderDialogsProps) {
  const clearFolderError = useDocumentStore((s) => s.clearFolderError)
  const {
    newFolderDialogOpen,
    setNewFolderDialogOpen,
    renameFolderTarget,
    setRenameFolderTarget,
    folderMenu,
    setFolderMenu,
    handleCreateFolder,
    handleRenameFolder,
    handleDeleteFolder,
  } = folderActions

  return (
    <>
      <Menu
        anchorEl={folderMenu?.anchorEl ?? null}
        open={folderMenu != null}
        onClose={() => setFolderMenu(null)}
      >
        <MenuItem
          onClick={() => {
            if (folderMenu) setRenameFolderTarget(folderMenu.folder)
            setFolderMenu(null)
          }}
        >
          Umbenennen
        </MenuItem>
        <MenuItem
          onClick={() => {
            const folder = folderMenu?.folder
            setFolderMenu(null)
            if (folder) void handleDeleteFolder(folder)
          }}
        >
          Löschen
        </MenuItem>
      </Menu>

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
