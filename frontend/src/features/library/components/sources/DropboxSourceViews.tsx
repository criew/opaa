import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { dropboxSettingsOf } from '../../utils/dropboxSource'
import ScopeConsequence from './ScopeConsequence'
import type { StoredLibrarySource } from './types'

// The folders of a Dropbox library, one chip each, for every reader.
export function DropboxScope({ library }: { library: StoredLibrarySource }) {
  const settings = dropboxSettingsOf(library)
  const folders = settings.folders ?? []
  return (
    <>
      <Typography variant="body2" component="div">
        <strong>{settings.teamSpace ? 'Ordner im Team-Bereich:' : 'Ordner:'}</strong>{' '}
        <Stack direction="row" spacing={0.5} useFlexGap component="span" sx={{ flexWrap: 'wrap' }}>
          {folders.map((folder) => (
            <Chip key={folder} size="small" label={folder} sx={{ fontFamily: 'monospace' }} />
          ))}
        </Stack>
      </Typography>
      <ScopeConsequence testId="dropbox-sharing-consequence">
        Gelesen wird, was das verbundene Dropbox-Konto in diesen Ordnern sieht, auch geteilte
        Ordner. Alles, was daraus indiziert wurde, ist für alle Leseberechtigten dieser Bibliothek
        sichtbar - unabhängig davon, wer es in Dropbox lesen dürfte.
      </ScopeConsequence>
    </>
  )
}

export function DropboxStoredView({
  library,
}: {
  library: StoredLibrarySource
  libraryId: string
}) {
  const settings = dropboxSettingsOf(library)
  return (
    <>
      <Typography variant="body2">
        <strong>Bereich:</strong>{' '}
        {settings.teamSpace ? 'Team-Bereich' : 'Ordner des verbundenen Kontos'}
      </Typography>
      <Typography variant="body2">
        <strong>Anmeldung:</strong> eigene Zustimmung der Bibliothek beim Zugang
      </Typography>
    </>
  )
}
