import type { SourceTypeKey } from '@/shared/api/types'
import { browseSource } from '../../../api/libraryApi'
import type { ConnectionFields } from '../../sources/sourceConnection'
import PickCombo, { type PickListing, type PickNode } from '../PickCombo'
import { ALL_FOLDERS, folderNodes, folderText } from './folderNodes'

interface FolderScopeProps {
  id: string
  sourceType: SourceTypeKey
  folders: string
  onChange: (folders: string) => void
  fields: ConnectionFields
  /** Address and credentials the listing signs in with. */
  connection: { sourceUrl: string; sourceCredentials?: string }
  query?: Record<string, unknown>
  error?: string | null
}

/**
 * The folders of a path source (Nextcloud, Windows-Dateifreigabe, Dropbox): „/ (alles)“ is the
 * default, any folder below can be picked level by level or by typing its path.
 */
export default function FolderScope({
  id,
  sourceType,
  folders,
  onChange,
  fields,
  connection,
  query = {},
  error,
}: FolderScopeProps) {
  const load = async (parent: PickNode | null): Promise<PickListing> => {
    const path = parent?.key ?? '/'
    const result = await browseSource(
      sourceType,
      fields.probeRequest({ ...connection, query: { ...query, path } }),
    )
    const trail = parent ? [...parent.trail, parent.label] : []
    return {
      complete: result.complete,
      message: result.message,
      nodes: result.entries.map((entry) => {
        const segments = entry.key.split('/').filter(Boolean)
        return {
          key: entry.key.startsWith('/') ? entry.key : `/${entry.key}`,
          label: entry.name ?? segments[segments.length - 1] ?? entry.key,
          trail,
          branch: true,
        }
      }),
    }
  }
  return (
    <PickCombo
      id={id}
      label="Ordner"
      hint="„/ (alles)“ liest die ganze Ablage. Unterordner öffnen Sie mit dem Pfeil oder tippen den Pfad, z. B. Projekte/2024."
      error={error}
      noun={['Ordner', 'Ordner']}
      selected={folderNodes(folders)}
      onChange={(nodes) => onChange(folderText(nodes))}
      load={load}
      hierarchical
      allNode={ALL_FOLDERS}
      max={50}
      manual={(text) => {
        const path = text.replace(/\/+$/, '')
        if (!path.startsWith('/') || path.length < 2) return null
        return folderNodes(path)[0] ?? null
      }}
      manualLabel={(text) => `Pfad „${text.replace(/\/+$/, '')}“ übernehmen`}
      loadKey={JSON.stringify({ connection, query, probe: fields.probe })}
      placeholder="Ordner suchen oder Pfad tippen"
    />
  )
}
