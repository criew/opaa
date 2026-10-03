import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { smbSettingsOf } from '../../../utils/smbSource'
import ScopeConsequence from './ScopeConsequence'
import type { StoredLibrarySource } from './types'

// The folders of an SMB library, one chip each, for every reader.
export function SmbScope({ library }: { library: StoredLibrarySource }) {
  const folders = smbSettingsOf(library).folders ?? ['/']
  return (
    <>
      <Typography variant="body2" component="div">
        <strong>Ordner:</strong>{' '}
        <Stack direction="row" spacing={0.5} useFlexGap component="span" sx={{ flexWrap: 'wrap' }}>
          {folders.map((folder) => (
            <Chip key={folder} size="small" label={folder} sx={{ fontFamily: 'monospace' }} />
          ))}
        </Stack>
      </Typography>
      <ScopeConsequence testId="smb-sharing-consequence">
        Gelesen wird, was das Dienstkonto in diesen Ordnern lesen darf. Alles, was daraus indiziert
        wurde, ist für alle Leseberechtigten dieser Bibliothek sichtbar - unabhängig von den
        Dateirechten auf der Freigabe.
      </ScopeConsequence>
    </>
  )
}

export function SmbStoredView({ library }: { library: StoredLibrarySource; libraryId: string }) {
  return (
    <>
      <Typography variant="body2">
        <strong>Freigabe:</strong> {library.sourceUrl ?? '—'}
      </Typography>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        Zugangsdaten sind aus Sicherheitsgründen nie Teil einer API-Antwort - diese Ansicht zeigt
        sie deshalb weder ein noch aus.
      </Typography>
    </>
  )
}
