import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import VpnKeyOutlinedIcon from '@mui/icons-material/VpnKeyOutlined'
import type { ConnectionProfileResponse } from '../types/api'
import { useAuthStore } from '../stores/authStore'
import { confirmAction } from '../stores/confirmStore'
import { notify } from '../stores/notificationStore'
import { useSourceTypes } from '../hooks/useSourceTypes'
import {
  deleteConnectionProfile,
  disconnectAllConnections,
  getConnectionProfileImpact,
  listConnectionProfiles,
} from '../services/connectionProfileApi'
import PageHeading from '../components/a11y/PageHeading'
import AreaPageHeader from '../components/AreaPageHeader'
import ConnectionProfileFormDialog from '../components/admin/connections/ConnectionProfileFormDialog'
import {
  AUTH_METHOD_LABELS,
  OWNERSHIP_LABELS,
} from '../components/admin/connections/connectionProfileLabels'
import { contentWidth } from '../theme/tokens'

/** "31.03.2027" for an ISO date, read as a calendar day without a time zone shift. */
function formatDay(isoDate: string) {
  const [year, month, day] = isoDate.split('-')
  return `${day}.${month}.${year}`
}

/** Whether the ISO date lies before today. */
function isPast(isoDate: string) {
  const today = new Date()
  const iso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
  return isoDate < iso
}

function connectionCount(count: number) {
  return count === 1 ? '1 Verbindung' : `${count} Verbindungen`
}

/**
 * Die Zugänge der Installation (#2160): je Zugang Quellart, Server-Adresse, Anmeldeart und Zahl
 * der Verbindungen, dazu Bearbeiten, die Notabschaltung „Alle Verbindungen trennen“ und Löschen.
 * Beide letzteren fragen mit der Zahl der Betroffenen nach.
 */
export default function ConnectionProfileManagementPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const { sourceTypes, loaded: sourceTypesLoaded } = useSourceTypes()
  const anyTypeAdmitsProfiles = sourceTypes.some((type) => type.profileSupport !== 'FORBIDDEN')
  const [profiles, setProfiles] = useState<ConnectionProfileResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState<{ open: boolean; profile: ConnectionProfileResponse | null }>({
    open: false,
    profile: null,
  })

  const [reloadKey, setReloadKey] = useState(0)
  const reload = () => setReloadKey((key) => key + 1)

  useEffect(() => {
    if (!isSystemAdmin) return
    let cancelled = false
    void listConnectionProfiles()
      .then((loaded) => {
        if (cancelled) return
        setProfiles(loaded)
        setError(null)
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Zugänge konnten nicht geladen werden.')
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [isSystemAdmin, reloadKey])

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Zugänge" gutterBottom />
        <Alert severity="info">
          Zugänge werden von der Systemverwaltung gepflegt. Für Ihr Konto ist diese Seite nicht
          freigegeben.
        </Alert>
      </Box>
    )
  }

  const displayNameOf = (type: string) =>
    sourceTypes.find((descriptor) => descriptor.type === type)?.displayName ?? type

  async function handleDisconnect(profile: ConnectionProfileResponse) {
    try {
      const impact = await getConnectionProfileImpact(profile.id)
      const confirmed = await confirmAction({
        question: `Alle Verbindungen von „${profile.name}“ trennen?`,
        consequence: `Betroffen: ${connectionCount(impact.connections)}. Alle Zugangsdaten werden sofort verworfen. Der Zugang bleibt bestehen; die Bibliotheken laufen erst wieder, wenn ihre Zugangsdaten neu eingetragen sind.`,
        confirmLabel: 'Alle trennen',
        tone: 'danger',
      })
      if (!confirmed) return
      const result = await disconnectAllConnections(profile.id)
      notify(`${connectionCount(result.connections)} getrennt.`, 'success')
      reload()
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Trennen fehlgeschlagen.', 'error')
    }
  }

  async function handleDelete(profile: ConnectionProfileResponse) {
    try {
      const impact = await getConnectionProfileImpact(profile.id)
      const confirmed = await confirmAction({
        question: `Zugang „${profile.name}“ löschen?`,
        consequence: `Betroffen: ${connectionCount(impact.connections)}; sie werden getrennt. Die Bibliotheken bleiben mit ihrem Inhalt und dem Hinweis „Zugang entfernt“ stehen und laufen nicht mehr, bis sie einem anderen Zugang zugeordnet sind.`,
        confirmLabel: 'Löschen',
        tone: 'danger',
      })
      if (!confirmed) return
      await deleteConnectionProfile(profile.id)
      notify(`„${profile.name}“ wurde gelöscht.`, 'success')
      reload()
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Löschen fehlgeschlagen.', 'error')
    }
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={VpnKeyOutlinedIcon}
          title="Zugänge"
          description="Ein Zugang legt für eine Quellart Server, Anmeldeart und App-Registrierung fest. Bibliotheken auf einem Zugang tragen diese Angaben nicht selbst ein."
        />
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 2,
            flexWrap: 'wrap',
            mb: 2,
          }}
        >
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
            {profiles.length === 1 ? '1 Zugang' : `${profiles.length} Zugänge`}
          </Typography>
          <Button
            variant="contained"
            disabled={!anyTypeAdmitsProfiles}
            onClick={() => setForm({ open: true, profile: null })}
          >
            Neuer Zugang
          </Button>
        </Box>

        {sourceTypesLoaded && !anyTypeAdmitsProfiles && (
          <Alert severity="info" sx={{ mb: 2 }} data-testid="no-profile-source-type">
            Zugänge stehen zur Verfügung, sobald eine Quellart sie unterstützt. In dieser Version
            bietet noch keine Quellart Zugänge an.
          </Alert>
        )}

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {loading ? (
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
            Zugänge werden geladen …
          </Typography>
        ) : profiles.length === 0 ? (
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
            Es sind noch keine Zugänge angelegt.
          </Typography>
        ) : (
          <Table size="small" aria-label="Zugänge">
            <TableHead>
              <TableRow>
                <TableCell>Name</TableCell>
                <TableCell>Quellart</TableCell>
                <TableCell>Server-Adresse</TableCell>
                <TableCell>Anmeldung</TableCell>
                <TableCell>Verbindungen</TableCell>
                <TableCell align="right">Aktionen</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {profiles.map((profile) => (
                <TableRow key={profile.id}>
                  <TableCell>
                    <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                      <span>{profile.name}</span>
                      {profile.clientSecretExpiresSoon && profile.clientSecretExpiresOn && (
                        <Chip
                          size="small"
                          color={isPast(profile.clientSecretExpiresOn) ? 'error' : 'warning'}
                          label={
                            isPast(profile.clientSecretExpiresOn)
                              ? `Secret abgelaufen am ${formatDay(profile.clientSecretExpiresOn)}`
                              : `Secret läuft ab am ${formatDay(profile.clientSecretExpiresOn)}`
                          }
                        />
                      )}
                    </Stack>
                  </TableCell>
                  <TableCell>{displayNameOf(profile.sourceType)}</TableCell>
                  <TableCell sx={{ wordBreak: 'break-all' }}>{profile.serverUrl}</TableCell>
                  <TableCell>
                    {AUTH_METHOD_LABELS[profile.authMethod]}
                    <Typography variant="caption" component="div" sx={{ color: 'text.secondary' }}>
                      für {OWNERSHIP_LABELS[profile.ownership]}
                    </Typography>
                  </TableCell>
                  <TableCell>{profile.connectionCount}</TableCell>
                  <TableCell align="right">
                    <Stack
                      direction="row"
                      spacing={1}
                      sx={{ justifyContent: 'flex-end', flexWrap: 'wrap' }}
                    >
                      <Button
                        size="small"
                        onClick={() => setForm({ open: true, profile })}
                        aria-label={`${profile.name} bearbeiten`}
                      >
                        Bearbeiten
                      </Button>
                      <Button
                        size="small"
                        color="warning"
                        onClick={() => void handleDisconnect(profile)}
                        aria-label={`Alle Verbindungen von ${profile.name} trennen`}
                      >
                        Alle Verbindungen trennen
                      </Button>
                      <Button
                        size="small"
                        color="error"
                        onClick={() => void handleDelete(profile)}
                        aria-label={`${profile.name} löschen`}
                      >
                        Löschen
                      </Button>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}

        <ConnectionProfileFormDialog
          key={form.profile?.id ?? (form.open ? 'new-open' : 'new')}
          open={form.open}
          profile={form.profile}
          sourceTypes={sourceTypes}
          onClose={() => setForm({ open: false, profile: null })}
          onSaved={(saved) => {
            notify(`„${saved.name}“ wurde gespeichert.`, 'success')
            reload()
          }}
        />
      </Box>
    </Box>
  )
}
