import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { confluenceSettingsOf } from '../../../utils/confluenceSource'
import { confluenceEditionLabel } from '../../../utils/labels'
import ConfluenceWebhookSection from '../ConfluenceWebhookSection'
import { RemoteConnectionLines } from './GenericSourceViews'
import ScopeConsequence from './ScopeConsequence'
import type { StoredLibrarySource } from './types'

// #1138 (ADR-0023): the selected spaces are the scope every reader of the library sees.
export function ConfluenceScope({ library }: { library: StoredLibrarySource }) {
  const settings = confluenceSettingsOf(library)
  return (
    <>
      <Typography variant="body2">
        <strong>Edition:</strong>{' '}
        {settings.edition ? confluenceEditionLabel(settings.edition) : '—'}{' '}
        <Typography component="span" variant="caption" sx={{ color: 'text.secondary' }}>
          (erkannt, nach der Anlage nicht änderbar)
        </Typography>
      </Typography>
      <Typography variant="body2" component="div">
        <strong>Ausgewählte Spaces:</strong>{' '}
        <Stack direction="row" spacing={0.5} useFlexGap component="span" sx={{ flexWrap: 'wrap' }}>
          {(settings.spaces ?? []).map((space) => (
            <Chip
              key={space.key}
              size="small"
              label={space.name ? `${space.name} (${space.key})` : space.key}
            />
          ))}
        </Stack>
      </Typography>
      <ScopeConsequence testId="confluence-sharing-consequence">
        Ein Geltungsbereich ist der Ausschnitt der Quelle, den diese Bibliothek spiegelt — hier die
        ausgewählten Confluence-Spaces. Alles, was daraus indiziert wurde, ist für alle
        Leseberechtigten dieser Bibliothek sichtbar — unabhängig davon, wer es in Confluence lesen
        dürfte. Was das hinterlegte Dienstkonto in Confluence nicht lesen darf, nimmt OPAA nicht
        auf: Seiten, die es gar nicht erst sieht, tauchen nirgends auf; wo ein Abruf oder ein ganzer
        Space scheitert, weist das Laufprotokoll das aus.
      </ScopeConsequence>
    </>
  )
}

export function ConfluenceStoredView({
  library,
  libraryId,
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
      <ConfluenceWebhookSection libraryId={libraryId} secretSet={library.pushSecretSet} />
    </>
  )
}
