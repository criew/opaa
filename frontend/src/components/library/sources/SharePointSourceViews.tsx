import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import {
  sharePointCoverageLabel,
  sharePointLibrariesOf,
  sharePointLibraryLabel,
  sharePointSettingsFromLibrary,
} from '../../../utils/sharePointSource'
import { RemoteConnectionLines } from './GenericSourceViews'
import ScopeConsequence from './ScopeConsequence'
import type { StoredLibrarySource } from './types'

// ADR-0040, Nachtrag „SharePoint“: the document libraries of a SharePoint library, one chip each.
export function SharePointScopeView({ library }: { library: StoredLibrarySource }) {
  const libraries = sharePointLibrariesOf(sharePointSettingsFromLibrary(library))
  return (
    <>
      <Typography variant="body2" component="div">
        <strong>Dokumentbibliotheken:</strong>{' '}
        <Stack direction="row" spacing={0.5} useFlexGap component="span" sx={{ flexWrap: 'wrap' }}>
          {libraries.map((entry) => (
            <Chip
              key={entry.driveId}
              size="small"
              label={`${sharePointLibraryLabel(entry)} · ${sharePointCoverageLabel(entry)}`}
            />
          ))}
        </Stack>
      </Typography>
      <ScopeConsequence testId="sharepoint-sharing-consequence">
        Eine Dokumentbibliothek wird ganz oder nur mit den gewählten Ordnern samt Unterordnern
        gelesen. Alles, was daraus indiziert wurde, ist für alle Leseberechtigten dieser Bibliothek
        sichtbar, unabhängig von den Berechtigungen in SharePoint.
      </ScopeConsequence>
    </>
  )
}

export function SharePointStoredView({ library }: { library: StoredLibrarySource }) {
  return (
    <>
      <Typography variant="body2">
        <strong>Anmeldung:</strong> über den Zugang (App-Registrierung in Microsoft Entra); die
        Bibliothek trägt keine eigenen Zugangsdaten
      </Typography>
      <RemoteConnectionLines library={library} />
    </>
  )
}
