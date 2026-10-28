import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Link as RouterLink, useParams } from 'react-router'
import ChecklistOutlinedIcon from '@mui/icons-material/ChecklistOutlined'
import type { GroupEffectsResponse } from '@/shared/api/types'
import { getGroupEffects } from '@/features/succession'
import { useAuthStore } from '@/shared/session/authStore'
import { useOidcProviderStore } from '@/features/identity-provider'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import MetaBadge from '@/shared/ui/MetaBadge'
import { PermissionTransferDialog } from '@/features/succession'
import { groupUsageDetails } from '../components/groupUsageLabels'
import { contentWidth } from '@/shared/theme/tokens'

/**
 * The worklist per provider (ADR-0036 Entscheidung 2): the `409` when deleting a
 * provider points here. Per group it shows what it is used for, and the way out — transfer its
 * rights or remove it where it is granted. Groups not in use go with the
 * provider.
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

  // No synchronous setState in the body: the effect below calls the same function, and a
  // synchronous setState from there causes a render cascade (react-hooks/set-state-in-effect).
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
          description="Der Anbieter lässt sich erst löschen, wenn keine dieser Gruppen mehr verwendet wird: Übertragen Sie ihre Rechte an eine andere Gruppe oder entfernen Sie sie an der Bibliothek oder im Space."
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
