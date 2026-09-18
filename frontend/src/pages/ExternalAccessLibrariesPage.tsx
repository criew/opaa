import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import PublicOutlinedIcon from '@mui/icons-material/PublicOutlined'
import AreaPageHeader from '../components/AreaPageHeader'
import type { ExternalAccessLibraryResponse } from '../types/api'
import { getExternalAccessLibraries } from '../services/api'
import { contentWidth } from '../theme/tokens'

function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  return new Date(value).toLocaleDateString('de-DE')
}

/**
 * Der Bestand der aktuell für Fremdzugänge freigegebenen Bibliotheken (#1731). Bewusst nur die
 * gültigen: Die Liste macht den Bestand prüfbar, und eine Liste aller je freigegebenen Bibliotheken
 * begrübe genau das. Sie nennt keine Person, die ein Zugangstoken hält - nur die Stelle, die
 * freigegeben hat.
 */
export default function ExternalAccessLibrariesPage() {
  const [libraries, setLibraries] = useState<ExternalAccessLibraryResponse[]>([])
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    void getExternalAccessLibraries()
      .then((result) => {
        setLibraries(result)
        setError(null)
      })
      .catch((err: unknown) =>
        setError(err instanceof Error ? err.message : 'Die Liste konnte nicht geladen werden'),
      )
      .finally(() => setIsLoading(false))
  }, [])

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={PublicOutlinedIcon}
          title="Fremdzugangsfreigaben"
          meta={libraries.length === 1 ? '1 Bibliothek' : `${libraries.length} Bibliotheken`}
          description="Bibliotheken, die derzeit über Fremdzugänge genutzt werden dürfen. Jede Freigabe ist befristet und erlischt ohne Zutun; erneuern kann sie nur, wer die Bibliothek verantwortet."
        />

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {isLoading ? (
          <Typography sx={{ color: 'text.secondary' }}>Freigaben werden geladen …</Typography>
        ) : libraries.length === 0 ? (
          <Typography sx={{ color: 'text.secondary' }}>
            Derzeit ist keine Bibliothek für Fremdzugänge freigegeben.
          </Typography>
        ) : (
          <Stack spacing={0.5}>
            {libraries.map((entry) => (
              <Box
                key={entry.libraryId}
                sx={{
                  py: 1.25,
                  borderBottom: 1,
                  borderColor: 'divider',
                  '&:last-of-type': { borderBottom: 0 },
                }}
              >
                <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>
                  {entry.libraryName}
                </Typography>
                <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                  Freigegeben bis {formatDate(entry.externalAccess.expiresAt)} · gesetzt am{' '}
                  {formatDate(entry.externalAccess.setAt)} von{' '}
                  {entry.externalAccess.setByDisplayName ?? 'unbekannt'} ·{' '}
                  {entry.externalAccess.tokenCount} Zugangstokens
                </Typography>
              </Box>
            ))}
          </Stack>
        )}
      </Box>
    </Box>
  )
}
