import { useCallback, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import {
  SHAREPOINT_API,
  sharePointKeyOf,
  sharePointLibraryLabel,
  sharePointPayloadOf,
  type SharePointLibraryChoice,
  type SharePointSourceValues,
} from '../../utils/sharePointSource'
import { connectionFields } from '../sources/sourceConnection'
import type { SourceFormProps } from '../sources/types'
import ConnectionCheck from './ConnectionCheck'
import SelectionTree, { type TreeListing, type TreeNode, type TreeSelection } from './SelectionTree'
import { ScopeField } from './SetupField'
import SourceSetupLayout from './SourceSetupLayout'
import { browseWith, listingNote, testRequestOf, tokenOf } from './probe'

/** The stored libraries as tree selections; a library with folders contributes its folders. */
function librariesToSelection(libraries: SharePointLibraryChoice[]): TreeSelection[] {
  return libraries.flatMap((library) => {
    const libraryNode: TreeNode = {
      id: `d:${library.driveId}`,
      label: sharePointLibraryLabel(library),
    }
    if (library.folders.length === 0)
      return [{ id: libraryNode.id, label: libraryNode.label, ancestors: [] }]
    return library.folders.map((folder) => ({
      id: `f:${library.driveId}:${folder.id}`,
      label: folder.name ?? folder.id,
      ancestors: [libraryNode],
    }))
  })
}

function selectionToLibraries(selection: TreeSelection[]): SharePointLibraryChoice[] {
  const byDrive = new Map<string, SharePointLibraryChoice>()
  for (const entry of selection) {
    if (entry.id.startsWith('d:')) {
      const driveId = entry.id.slice(2)
      const site = entry.ancestors.find((a) => a.id.startsWith('s:'))
      byDrive.set(driveId, {
        driveId,
        name: site && !entry.label.includes('(') ? `${entry.label} (${site.label})` : entry.label,
        folders: [],
      })
    }
  }
  for (const entry of selection) {
    if (!entry.id.startsWith('f:')) continue
    const [, driveId, folderId] = /^f:([^:]+):(.+)$/.exec(entry.id) ?? []
    if (!driveId) continue
    const libraryNode = entry.ancestors.find((a) => a.id === `d:${driveId}`)
    const site = entry.ancestors.find((a) => a.id.startsWith('s:'))
    const library = byDrive.get(driveId) ?? {
      driveId,
      name: libraryNode
        ? site && !libraryNode.label.includes('(')
          ? `${libraryNode.label} (${site.label})`
          : libraryNode.label
        : null,
      folders: [],
    }
    const trail = entry.ancestors.filter((a) => a.id.startsWith('f:')).map((a) => a.label)
    library.folders.push({ id: folderId, name: [...trail, entry.label].join(' / ') })
    byDrive.set(driveId, library)
  }
  return [...byDrive.values()]
}

/**
 * SharePoint on the shared frame: the Zugang signs in (no Zugangsdaten section); Umfang finds
 * sites by name or address and chooses document libraries and folders in the shared tree.
 */
export default function SharePointSetup({
  values,
  onChange,
  context,
}: SourceFormProps<SharePointSourceValues>) {
  const fields = connectionFields(context)
  const [query, setQuery] = useState('')
  const [search, setSearch] = useState<{ text: string; generation: number } | null>(null)

  const loadChildren = useCallback(
    async (node: TreeNode | null): Promise<TreeListing> => {
      const browse = (q: Record<string, unknown>) =>
        browseWith(context, { sourceUrl: SHAREPOINT_API, query: q })
      if (!node) {
        const text = search?.text ?? ''
        const response = await browse(
          /^https?:\/\//i.test(text) ? { siteUrl: text } : { search: text },
        )
        return {
          items: response.entries.flatMap((entry) => {
            const key = sharePointKeyOf(entry.key)
            return key?.kind === 'site'
              ? [{ id: `s:${key.id}`, label: entry.name ?? key.id, selectable: false }]
              : []
          }),
          note: listingNote(response),
        }
      }
      const response = node.id.startsWith('s:')
        ? await browse({ site: node.id.slice(2) })
        : node.id.startsWith('d:')
          ? await browse({ drive: node.id.slice(2) })
          : await browse({
              drive: node.id.split(':')[1],
              folder: node.id.split(':').slice(2).join(':'),
            })
      const driveId = node.id.startsWith('d:') ? node.id.slice(2) : node.id.split(':')[1]
      return {
        items: response.entries.flatMap((entry) => {
          const key = sharePointKeyOf(entry.key)
          if (key?.kind === 'drive') return [{ id: `d:${key.id}`, label: entry.name ?? key.id }]
          if (key?.kind === 'folder')
            return [{ id: `f:${driveId}:${key.id}`, label: entry.name ?? key.id }]
          return []
        }),
        note: listingNote(response),
      }
    },
    // the tree is remounted with every search
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [search?.generation],
  )

  const runSearch = () =>
    setSearch({ text: query.trim(), generation: (search?.generation ?? 0) + 1 })
  const selection = librariesToSelection(values.libraries)
  return (
    <SourceSetupLayout
      connection={
        <Typography sx={{ fontSize: 13.5 }}>
          {fields.connection
            ? `Angemeldet über den Zugang „${fields.connection.name}“.`
            : 'SharePoint ist nur über einen Zugang nutzbar. Bitte oben einen Zugang wählen.'}
        </Typography>
      }
      check={
        <ConnectionCheck
          stages={['signIn', 'access']}
          token={tokenOf(fields.probe, values.libraries)}
          request={() =>
            fields.connection || context.mode === 'edit'
              ? testRequestOf(context, sharePointPayloadOf(values))
              : null
          }
          missingHint="Bitte zuerst einen Zugang wählen."
        />
      }
      scope={
        <ScopeField field="libraries">
          <Box>
            <FieldLabel htmlFor={`${context.idPrefix}-sharepoint-search`}>
              Site suchen oder Adresse
            </FieldLabel>
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
              <TextField
                id={`${context.idPrefix}-sharepoint-search`}
                size="small"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault()
                    runSearch()
                  }
                }}
                placeholder="Rathaus oder https://…/sites/rathaus"
                sx={{ flex: '1 1 240px' }}
              />
              <Button variant="outlined" onClick={runSearch}>
                Sites suchen
              </Button>
            </Box>
            <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.5 }}>
              Leer lassen, um alle Sites zu sehen, die der Zugang lesen darf.
            </Typography>
          </Box>
          {search && (
            <Box key={search.generation} sx={{ mt: 2 }}>
              <SelectionTree
                label="Sites"
                noun={{ one: 'Eintrag', many: 'Einträge' }}
                rootLabel="Sites"
                loadChildren={loadChildren}
                selection={selection}
                onChange={(next) => onChange({ libraries: selectionToLibraries(next) })}
                openLabel={(node) =>
                  node.id.startsWith('s:')
                    ? `Bibliotheken von „${node.label}“ anzeigen`
                    : node.id.startsWith('d:')
                      ? `Ordner von „${node.label}“ anzeigen`
                      : `Unterordner von „${node.label}“ anzeigen`
                }
                emptyText={
                  search.text
                    ? `Keine Site passt zu „${search.text}“.`
                    : 'Der Zugang sieht keine Sites.'
                }
              />
            </Box>
          )}
          {!search && selection.length > 0 && (
            <Typography sx={{ fontSize: 13, mt: 1.5 }}>
              Gewählt: {values.libraries.map(sharePointLibraryLabel).join(', ')}
            </Typography>
          )}
        </ScopeField>
      }
    />
  )
}
