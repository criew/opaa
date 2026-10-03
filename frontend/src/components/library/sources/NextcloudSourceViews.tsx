import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { nextcloudSettingsOf } from '../../../utils/nextcloudSource'
import { RemoteConnectionLines } from './GenericSourceViews'
import ScopeConsequence from './ScopeConsequence'
import type { StoredLibrarySource } from './types'

// The folders of a Nextcloud library, one chip each, for every reader.
export function NextcloudScope({ library }: { library: StoredLibrarySource }) {
  const folders = nextcloudSettingsOf(library).folders ?? []
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
      <ScopeConsequence testId="nextcloud-sharing-consequence">
        Gelesen wird, was der technische Nutzer in diesen Ordnern sieht, auch Freigaben an ihn und
        Gruppenordner. Alles, was daraus indiziert wurde, ist für alle Leseberechtigten dieser
        Bibliothek sichtbar - unabhängig davon, wer es in der Nextcloud lesen dürfte.
      </ScopeConsequence>
    </>
  )
}

export function NextcloudStoredView({
  library,
}: {
  library: StoredLibrarySource
  libraryId: string
}) {
  return (
    <>
      <Typography variant="body2">
        <strong>Adresse:</strong> {library.sourceUrl ?? '—'}
      </Typography>
      <RemoteConnectionLines library={library} />
    </>
  )
}
