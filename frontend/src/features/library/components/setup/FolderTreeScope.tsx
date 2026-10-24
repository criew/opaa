import { useCallback } from 'react'
import type { SourceBrowseResponse } from '@/shared/api/types'
import { issueMessage, fieldId, useSetup } from './setupContext'
import SelectionTree, { type TreeListing, type TreeNode, type TreeSelection } from './SelectionTree'
import ScopeLoader from './ScopeLoader'
import { listingNote } from './probe'
import Typography from '@mui/material/Typography'
import Box from '@mui/material/Box'

const ROOT_NODE: TreeNode = { id: '/', label: 'Alle Ordner' }

/** The folders of the form (one path per line) as tree selections, ancestors from the path. */
export function foldersToSelection(text: string): TreeSelection[] {
  const folders = [...new Set(text.split('\n').map((line) => line.trim()).filter(Boolean))]
  return folders.map((folder) => {
    if (folder === '/') return { id: '/', label: ROOT_NODE.label, ancestors: [] }
    const segments = folder.split('/').filter(Boolean)
    const ancestors: TreeNode[] = [ROOT_NODE]
    segments.slice(0, -1).forEach((segment, index) => {
      ancestors.push({ id: `/${segments.slice(0, index + 1).join('/')}`, label: segment })
    })
    return { id: `/${segments.join('/')}`, label: segments[segments.length - 1], ancestors }
  })
}

export function selectionToFolders(selection: TreeSelection[]): string {
  return selection.map((entry) => entry.id).join('\n')
}

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
export default function FolderTreeScope({ folders, onChange, browse, token, autoLoad, rootLabel = 'Alle Ordner' }: FolderTreeScopeProps) {
  const { idPrefix, issues } = useSetup()
  const message = issueMessage(issues, 'folders')
  const selection = foldersToSelection(folders)
  const loadChildren = useCallback(
    async (node: TreeNode | null): Promise<TreeListing> => {
      if (!node) return { items: [{ ...ROOT_NODE, label: rootLabel }] }
      const response = await browse(node.id)
      return {
        items: response.entries.map((entry) => {
          const path = entry.key.startsWith('/') ? entry.key : `/${entry.key}`
          return { id: path.replace(/\/+$/, '') || '/', label: entry.name ?? path.split('/').pop() ?? path }
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
    <Box id={fieldId(idPrefix, 'folders')} tabIndex={-1} sx={{ outline: 'none' }}>
      {message && (
        <Typography sx={{ fontSize: 12, color: 'error.main', mb: 1 }} role="alert">
          {message}
        </Typography>
      )}
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
    </Box>
  )
}
