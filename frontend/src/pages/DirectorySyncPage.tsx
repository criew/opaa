import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import SyncOutlinedIcon from '@mui/icons-material/SyncOutlined'
import type { DirectorySyncStatusResponse } from '../types/api'
import { getDirectorySyncStatus } from '../services/directorySyncApi'
import { useAuthStore } from '../stores/authStore'
import { useOidcProviderStore } from '../stores/oidcProviderStore'
import AreaPageHeader from '../components/AreaPageHeader'
import PageHeading from '../components/a11y/PageHeading'
import DirectorySyncProviderCard from '../components/admin/directorysync/DirectorySyncProviderCard'
import { contentWidth } from '../theme/tokens'

/**
 * Der Verzeichnisabgleich je Anbieter (#1821, ADR-0036 Entscheidung 3): Status, Trockenlauf, Lauf
 * und die Entscheidung über einen ausstehenden Plan — je Anbieter, nicht je Installation.
 */
export default function DirectorySyncPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const providers = useOidcProviderStore((s) => s.providers)
  const loadProviders = useOidcProviderStore((s) => s.loadProviders)
  const [statuses, setStatuses] = useState<DirectorySyncStatusResponse[]>([])
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(isSystemAdmin)

  // Kein synchrones setState im Rumpf: Der Effekt unten ruft dieselbe Funktion, und ein synchrones
  // setState von dort erzeugt eine Renderkaskade (react-hooks/set-state-in-effect).
  const reload = useCallback(
    () =>
      Promise.all([loadProviders(), getDirectorySyncStatus()])
        .then(([, loadedStatuses]) => {
          setStatuses(loadedStatuses)
          setError(null)
        })
        .catch((err: unknown) =>
          setError(
            err instanceof Error ? err.message : 'Der Abgleichstand konnte nicht geladen werden.',
          ),
        )
        .finally(() => setIsLoading(false)),
    [loadProviders],
  )

  useEffect(() => {
    if (!isSystemAdmin) return
    void reload()
  }, [isSystemAdmin, reload])

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Verzeichnisabgleich" gutterBottom />
        <Alert severity="info">
          Den Verzeichnisabgleich führt die Systemverwaltung. Für Ihr Konto ist diese Seite nicht
          freigegeben.
        </Alert>
      </Box>
    )
  }

  const syncProviders = providers.filter((provider) => provider.providerType === 'OIDC')

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={SyncOutlinedIcon}
          title="Verzeichnisabgleich"
          meta={syncProviders.length === 1 ? '1 Anbieter' : `${syncProviders.length} Anbieter`}
          description="Je Anbieter genau ein Gruppenmechanismus: entweder der Token-Claim bei jeder Anmeldung oder der zeitgesteuerte Verzeichnisabgleich. OPAA schreibt nie ins Verzeichnis."
        />

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {isLoading ? (
          <Typography sx={{ color: 'text.secondary' }}>Abgleichstand wird geladen …</Typography>
        ) : syncProviders.length === 0 ? (
          <Typography sx={{ color: 'text.secondary' }}>
            Es ist kein Identitätsanbieter hinterlegt, für den ein Verzeichnisabgleich in Frage
            käme.
          </Typography>
        ) : (
          <Stack spacing={2}>
            {syncProviders.map((provider) => (
              <DirectorySyncProviderCard
                key={provider.id}
                provider={provider}
                status={statuses.find((status) => status.providerId === provider.id)}
                onChanged={reload}
              />
            ))}
          </Stack>
        )}
      </Box>
    </Box>
  )
}
