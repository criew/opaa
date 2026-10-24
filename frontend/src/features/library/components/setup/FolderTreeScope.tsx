import { useCallback } from 'react'
import type { SourceBrowseResponse } from '@/shared/api/types'
import { ScopeField } from './SetupField'
import SelectionTree, { type TreeListing, type TreeNode } from './SelectionTree'
import {
  FOLDER_ROOT as ROOT_NODE,
  foldersToSelection,
  selectionToFolders,
} from './selectionMapping'
import ScopeLoader from './ScopeLoader'
import { listingNote } from './probe'

interface FolderTreeScopeProps {
  /** One path per line, `/` for everything. */
  folders: string
  onChange: (folders: string) => void
  /** Lists the direct subfolders of a path. */
  browse: (path: string) => Promise<SourceBrowseResponse>
  /** The values the listing signs in with. */
  token: string
  autoLoad?: boolean
  /** Where the tree starts; „Alle Ordner“ for the root of a share. */
  rootLabel?: string
}

/**
 * „Umfang“ of the folder sources (Nextcloud, Windows-Dateifreigabe, Dropbox): „Alle Ordner“ by
 * default, or single folders chosen in the shared tree, any level deep.
 */
export default function FolderTreeScope({
  folders,
  onChange,
  browse,
  token,
  autoLoad,
  rootLabel = 'Alle Ordner',
}: FolderTreeScopeProps) {
  const selection = foldersToSelection(folders)
  const loadChildren = useCallback(
    async (node: TreeNode | null): Promise<TreeListing> => {
      if (!node) return { items: [{ ...ROOT_NODE, label: rootLabel }] }
      const response = await browse(node.id)
      return {
        items: response.entries.map((entry) => {
          const path = entry.key.startsWith('/') ? entry.key : `/${entry.key}`
          return {
            id: path.replace(/\/+$/, '') || '/',
            label: entry.name ?? path.split('/').pop() ?? path,
          }
        }),
        note: listingNote(response),
      }
    },
    [browse, rootLabel],
  )
  const summary =
    selection.length === 0
      ? 'Kein Ordner gewählt.'
      : selection.some((entry) => entry.id === '/')
        ? 'Gelesen werden alle Ordner.'
        : `Gelesen werden ${selection.length} Ordner.`
  return (
    <ScopeField field="folders">
      <ScopeLoader
        loadLabel="Ordner laden"
        hint={`${summary} Laden Sie die Ordner, um einzelne zu wählen.`}
        token={token}
        autoLoad={autoLoad}
      >
        {() => (
          <SelectionTree
            label="Ordner"
            noun={{ one: 'Ordner', many: 'Ordner' }}
            rootLabel="Übersicht"
            loadChildren={loadChildren}
            selection={selection}
            onChange={(next) => onChange(selectionToFolders(next))}
            emptyText="Keine Ordner gefunden."
            initiallyExpanded={[ROOT_NODE]}
          />
        )}
      </ScopeLoader>
    </ScopeField>
  )
}
