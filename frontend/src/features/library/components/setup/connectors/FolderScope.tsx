import type { ConnectionFields } from '../../sources/sourceConnection'
import { folderItemsOf, folderSelectionSource } from '../../sourceKit/selectionSources'
import ScopePicker from '../ScopePicker'

const ROOT_LABELS: Record<'NEXTCLOUD' | 'SMB' | 'DROPBOX', string> = {
  NEXTCLOUD: 'Nextcloud',
  SMB: 'Freigabe',
  DROPBOX: 'Dropbox',
}

interface FolderScopeProps {
  id: string
  sourceType: 'NEXTCLOUD' | 'SMB' | 'DROPBOX'
  /** One path per line; „/“ reads the whole source. */
  folders: string
  onChange: (folders: string) => void
  fields: ConnectionFields
  /** Address and credentials the listing signs in with. */
  connection: { sourceUrl: string; sourceCredentials?: string }
  query?: Record<string, unknown>
  error?: string | null
}

/** The folders of a path source (Nextcloud, Windows-Dateifreigabe, Dropbox): all of it by default. */
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
  const whole = folders.split('\n').some((line) => line.trim() === '/')
  const source = folderSelectionSource(sourceType, ROOT_LABELS[sourceType], fields, () => ({
    ...connection,
    query,
  }))
  return (
    <ScopePicker
      id={id}
      source={source}
      value={whole ? [] : folderItemsOf(folders)}
      onChange={(items) => onChange(items.map((item) => item.key).join('\n'))}
      all={{ active: whole, onChange: (all) => onChange(all ? '/' : '') }}
      error={error}
      deeperLabel="Unterordner auswählen …"
      loadKey={JSON.stringify({ connection, query, probe: fields.probe })}
    />
  )
}
