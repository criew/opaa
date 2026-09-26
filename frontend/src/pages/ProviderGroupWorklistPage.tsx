import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Link as RouterLink, useParams } from 'react-router'
import ChecklistOutlinedIcon from '@mui/icons-material/ChecklistOutlined'
import type { GroupEffectsResponse } from '../types/api'
import { getGroupEffects } from '../services/permissionTransferApi'
import { useAuthStore } from '../stores/authStore'
import { useOidcProviderStore } from '../stores/oidcProviderStore'
import AreaPageHeader from '../components/AreaPageHeader'
import PageHeading from '../components/a11y/PageHeading'
import MetaBadge from '../components/MetaBadge'
import PermissionTransferDialog from '../components/permissions/PermissionTransferDialog'
import { groupUsageDetails } from '../components/admin/groups/groupUsageLabels'
import { contentWidth } from '../theme/tokens'

/**
 * Die Arbeitsliste je Anbieter (#1821, ADR-0036 Entscheidung 2): Der `409` beim Löschen eines
 * Anbieters verweist hierher. Je Gruppe steht, wofür sie verwendet wird, und der Ausgang — ihre
 * Rechte übertragen oder dort entfernen, wo sie vergeben sind. Nicht verwendete Gruppen gehen mit
 * dem Anbieter.
 */
export default function ProviderGroupWorklistPage() {
  const { providerId = '' } = useParams()
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const providers = useOidcProviderStore((s) => s.providers)
  const loadProviders = useOidcProviderStore((s) => s.loadProviders)
  const [effects, setEffects] = useState<GroupEffectsResponse[]>([])
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(isSystemAdmin)
  const [transferSource, setTransferSource] = useState<GroupEffectsResponse | null>(null)

  // Kein synchrones setState im Rumpf: Der Effekt unten ruft dieselbe Funktion, und ein synchrones
  // setState von dort erzeugt eine Renderkaskade (react-hooks/set-state-in-effect).
  const reload = useCallback(
    () =>
      getGroupEffects({ providerId })
        .then((loaded) => {
          setEffects(loaded)
          setError(null)
        })
        .catch((err: unknown) =>
          setError(
            err instanceof Error ? err.message : 'Die Arbeitsliste konnte nicht geladen werden.',
          ),
        )
        .finally(() => setIsLoading(false)),
    [providerId],
  )

  useEffect(() => {
    if (!isSystemAdmin) return
    void loadProviders()
    void reload()
  }, [isSystemAdmin, loadProviders, reload])

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Arbeitsliste des Anbieters" gutterBottom />
        <Alert severity="info">Für Ihr Konto ist diese Seite nicht freigegeben.</Alert>
      </Box>
    )
  }

  const provider = providers.find((entry) => entry.id === providerId)
  const reaching = effects.filter((entry) => groupUsageDetails(entry).length > 0)
  const idle = effects.filter((entry) => groupUsageDetails(entry).length === 0)

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={ChecklistOutlinedIcon}
          title={`Gruppen von „${provider?.displayName ?? 'Anbieter'}"`}
          meta={
            reaching.length === 1 ? '1 verwendete Gruppe' : `${reaching.length} verwendete Gruppen`
          }
          description="Solange eine Gruppe dieses Anbieters noch verwendet wird, lässt sich der Anbieter nicht löschen. Für jede dieser Gruppen gibt es zwei Wege: ihre Rechte an eine andere Gruppe übertragen oder sie dort entfernen, wo sie vergeben sind – an der Bibliothek oder im Space. Nicht verwendete Gruppen werden mit dem Anbieter gelöscht. Abgelaufene Berechtigungen zählen hier mit — sie halten das Löschen auf; die Vorschau der Übertragung lässt sie aus und kann deshalb kleinere Zahlen nennen."
        />

        <Link component={RouterLink} to="/admin/identity-providers" sx={{ fontSize: 13 }}>
          Zurück zur Anbieterverwaltung
        </Link>

        {error && (
          <Alert severity="error" sx={{ my: 2 }}>
            {error}
          </Alert>
        )}

        {isLoading ? (
          <Typography sx={{ color: 'text.secondary', mt: 2 }}>
            Arbeitsliste wird geladen …
          </Typography>
        ) : effects.length === 0 ? (
          <Typography sx={{ color: 'text.secondary', mt: 2 }}>
            Dieser Anbieter hat keine Gruppen.
          </Typography>
        ) : (
          <Stack spacing={1} sx={{ mt: 2 }}>
            {reaching.map((entry) => (
              <Box key={entry.groupId} sx={{ borderBottom: 1, borderColor: 'divider', pb: 1.5 }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{entry.name}</Typography>
                  {entry.dissolved && <MetaBadge>aufgelöst</MetaBadge>}
                  {entry.protectedGroup && <MetaBadge>geschützt</MetaBadge>}
                  <Button
                    size="small"
                    variant="outlined"
                    sx={{ ml: 'auto' }}
                    onClick={() => setTransferSource(entry)}
                  >
                    Rechte übertragen
                  </Button>
                </Stack>
                {entry.sourcePath && (
                  <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                    {entry.sourcePath}
                  </Typography>
                )}
                <Typography sx={{ fontSize: 13.5, mt: 0.5 }}>
                  {groupUsageDetails(entry).join(' · ')}
                </Typography>
              </Box>
            ))}
            {idle.length > 0 && (
              <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                Nicht verwendet, werden mit dem Anbieter gelöscht:{' '}
                {idle.map((entry) => entry.name).join(', ')}
              </Typography>
            )}
          </Stack>
        )}

        {transferSource && (
          <PermissionTransferDialog
            open
            onClose={() => setTransferSource(null)}
            source={{ type: 'GROUP', id: transferSource.groupId, name: transferSource.name }}
            targetKinds={['GROUP']}
            scopes={['ASSET_GRANTS', 'SPACE_MEMBERSHIPS', 'CAPABILITIES', 'OWNERSHIP']}
            intro={`Alle gewählten Rechte von „${transferSource.name}" gehen in einem Vorgang an die Zielgruppe. Die Vorschau ist Pflicht, die Bestätigung ausdrücklich.`}
            onTransferred={() => void reload()}
          />
        )}
      </Box>
    </Box>
  )
}
