import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { s3SettingsFromLibrary } from '../../../utils/s3Source'
import S3EventSection from '../S3EventSection'
import { RemoteConnectionLines } from './GenericSourceViews'
import ScopeConsequence from './ScopeConsequence'
import type { StoredLibrarySource } from './types'

// ADR-0027: the scopes of an S3 library, one chip per bucket/prefix, for every reader.
export function S3Scope({ library }: { library: StoredLibrarySource }) {
  const settings = s3SettingsFromLibrary(library)
  return (
    <>
      <Typography variant="body2" component="div">
        <strong>Geltungsbereiche:</strong>{' '}
        <Stack direction="row" spacing={0.5} useFlexGap component="span" sx={{ flexWrap: 'wrap' }}>
          {(settings?.scopes ?? []).map((scope) => (
            <Chip
              key={`${scope.bucket}/${scope.prefix ?? ''}`}
              size="small"
              label={scope.prefix ? `${scope.bucket}/${scope.prefix}` : scope.bucket}
              sx={{ fontFamily: 'monospace' }}
            />
          ))}
        </Stack>
      </Typography>
      <ScopeConsequence testId="s3-sharing-consequence">
        Ein Geltungsbereich ist ein Bucket des Objektspeichers, wahlweise eingegrenzt auf ein Präfix
        darin — nur was darunter liegt, nimmt diese Bibliothek auf. Alles, was daraus indiziert
        wurde, ist für alle Leseberechtigten dieser Bibliothek sichtbar — unabhängig davon, wer es
        im Objektspeicher lesen dürfte. Was der hinterlegte Schlüssel nicht lesen darf, nimmt OPAA
        nicht auf.
      </ScopeConsequence>
    </>
  )
}

export function S3StoredView({
  library,
  libraryId,
}: {
  library: StoredLibrarySource
  libraryId: string
}) {
  const settings = s3SettingsFromLibrary(library)
  return (
    <>
      <Typography variant="body2">
        <strong>Endpoint:</strong> {library.sourceUrl ?? '—'}
      </Typography>
      <Typography variant="body2">
        <strong>Region:</strong> {settings?.region ?? 'us-east-1 (Vorgabe)'} ·{' '}
        <strong>Adressstil:</strong> {settings?.pathStyle ? 'Path-Style' : 'Virtual-Host'}
      </Typography>
      {(settings?.includePatterns?.length ?? 0) > 0 && (
        <Typography variant="body2">
          <strong>Einschlussmuster:</strong> {settings?.includePatterns?.join(', ')}
        </Typography>
      )}
      {(settings?.excludePatterns?.length ?? 0) > 0 && (
        <Typography variant="body2">
          <strong>Ausschlussmuster:</strong> {settings?.excludePatterns?.join(', ')}
        </Typography>
      )}
      <RemoteConnectionLines library={library} />
      <S3EventSection
        libraryId={libraryId}
        tokenSet={library.pushSecretSet}
        scopes={settings?.scopes ?? []}
      />
    </>
  )
}
