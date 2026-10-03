import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import {
  googleDriveScopeKey,
  googleDriveScopeKindLabel,
  googleDriveScopeLabel,
  googleDriveScopesOf,
  googleDriveSettingsFromLibrary,
} from '../../../utils/googleDriveSource'
import { RemoteConnectionLines } from './GenericSourceViews'
import ScopeConsequence from './ScopeConsequence'
import type { StoredLibrarySource } from './types'

// ADR-0040: the areas of a Google Drive library, one chip each, for every reader.
export function GoogleDriveScopeView({ library }: { library: StoredLibrarySource }) {
  const scopes = googleDriveScopesOf(googleDriveSettingsFromLibrary(library))
  return (
    <>
      <Typography variant="body2" component="div">
        <strong>Bereiche:</strong>{' '}
        <Stack direction="row" spacing={0.5} useFlexGap component="span" sx={{ flexWrap: 'wrap' }}>
          {scopes.map((scope) => (
            <Chip
              key={googleDriveScopeKey(scope)}
              size="small"
              label={`${googleDriveScopeLabel(scope)} · ${googleDriveScopeKindLabel(scope.kind)}`}
            />
          ))}
        </Stack>
      </Typography>
      <ScopeConsequence testId="google-drive-sharing-consequence">
        Ein Bereich ist eine geteilte Ablage, ein Ordner samt Unterordnern oder „Meine Ablage“ des
        imitierten Kontos. Alles, was daraus indiziert wurde, ist für alle Leseberechtigten dieser
        Bibliothek sichtbar, unabhängig von den Freigaben in Google Drive.
      </ScopeConsequence>
    </>
  )
}

export function GoogleDriveStoredView({ library }: { library: StoredLibrarySource }) {
  const settings = googleDriveSettingsFromLibrary(library)
  return (
    <>
      <Typography variant="body2">
        <strong>Dienstkonto-Schlüssel:</strong>{' '}
        {library.sourceCredentialsSet ? 'gespeichert' : 'fehlt'}
      </Typography>
      <Typography variant="body2">
        <strong>Imitiertes Konto:</strong> {settings?.subject ?? 'keines (ohne Delegation)'}
      </Typography>
      <RemoteConnectionLines library={library} />
    </>
  )
}
