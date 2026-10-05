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
import type {
  ConnectionProfileImpactResponse,
  ConnectionProfileRequestResponse,
  ConnectionProfileResponse,
  ConnectorTypeStateResponse,
} from '../types/api'
import { useAuthStore } from '../stores/authStore'
import { confirmAction } from '../stores/confirmStore'
import { notify } from '../stores/notificationStore'
import { useSourceTypes } from '../hooks/useSourceTypes'
import {
  deleteConnectionProfile,
  disconnectAllConnections,
  getConnectionProfileImpact,
  listConnectionProfiles,
  listConnectorTypeStates,
  lockConnectionProfile,
  testConnectionProfileSignIn,
} from '../services/connectionProfileApi'
import PageHeading from '../components/a11y/PageHeading'
import AreaPageHeader from '../components/AreaPageHeader'
import ConnectionProfileFormDialog from '../components/admin/connections/ConnectionProfileFormDialog'
import ConnectorTypeLockSection from '../components/admin/connections/ConnectorTypeLockSection'
import ConnectionProfileRequestSection from '../components/admin/connections/ConnectionProfileRequestSection'
import ConnectionLogRetentionSection from '../components/admin/connections/ConnectionLogRetentionSection'
import PrivateStorageSection from '../components/admin/connections/PrivateStorageSection'
import { confirmLock } from '../components/admin/connections/connectorLock'
import {
  AUTH_METHOD_LABELS,
  OWNERSHIP_LABELS,
} from '../components/admin/connections/connectionProfileLabels'
import { admitsPersons, personCountLabel } from '../components/admin/connections/profileChange'
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

/** Whether the profile signs in itself, so its sign-in can be tested and rejected. */
function signsInItself(profile: ConnectionProfileResponse) {
  return profile.authMethod === 'CLIENT_CREDENTIALS' || profile.authMethod === 'SERVICE_ACCOUNT_KEY'
}

function connectionCount(count: number) {
  return count === 1 ? '1 Verbindung' : `${count} Verbindungen`
}

/** What the persons with a connected account learn and have to do after a shutdown. */
function personsNotice(profile: ConnectionProfileResponse): string {
  return admitsPersons(profile.ownership)
    ? ' Personen mit verbundenem Konto werden benachrichtigt und müssen ihr Konto selbst neu verbinden.'
    : ''
}

/** The persons' accounts a shutdown or deletion cuts off, as rounded as the API gives them. */
function accountsClause(
  profile: ConnectionProfileResponse,
  impact: ConnectionProfileImpactResponse,
): string {
  return admitsPersons(profile.ownership)
    ? `, verbundene Konten von Personen: ${personCountLabel(impact.connectedAccounts)}`
    : ''
}

/**
 * Die Zugänge der Installation (#2160): je Zugang Quellart, Server-Adresse, Anmeldeart und Zahl
 * der Verbindungen, dazu Bearbeiten, Sperren, die Notabschaltung „Alle Verbindungen trennen“ und
 * Löschen. Darunter die offenen Zugangswünsche und die Sperre je Quellart (#2161). Jede dieser
 * Handlungen fragt nach.
 */
export default function ConnectionProfileManagementPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const { sourceTypes, loaded: sourceTypesLoaded } = useSourceTypes()
  const anyTypeAdmitsProfiles = sourceTypes.some((type) => type.profileSupport !== 'FORBIDDEN')
  const [profiles, setProfiles] = useState<ConnectionProfileResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState<{
    open: boolean
    profile: ConnectionProfileResponse | null
    request?: ConnectionProfileRequestResponse
  }>({
    open: false,
    profile: null,
  })

  const [typeStates, setTypeStates] = useState<ConnectorTypeStateResponse[]>([])
  const [typeError, setTypeError] = useState<string | null>(null)
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
    void listConnectorTypeStates()
      .then((loaded) => {
        if (cancelled) return
        setTypeStates(loaded)
        setTypeError(null)
      })
      .catch((err) => {
        if (!cancelled) {
          setTypeError(
            err instanceof Error ? err.message : 'Quellarten konnten nicht geladen werden.',
          )
        }
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
        consequence: signsInItself(profile)
          ? `Betroffen: ${connectionCount(impact.connections)}${accountsClause(profile, impact)}. ${profile.authMethod === 'SERVICE_ACCOUNT_KEY' ? 'Der Dienstkonto-Schlüssel' : 'Das Client-Secret'} des Zugangs wird unwiderruflich gelöscht. Die Bibliotheken laufen erst wieder, wenn die Systemverwaltung ${profile.authMethod === 'SERVICE_ACCOUNT_KEY' ? 'einen Schlüssel' : 'ein Secret'} am Zugang neu hinterlegt. Beim Anbieter widerruft OPAA nichts; das geschieht dort.${personsNotice(profile)}`
          : `Betroffen: ${connectionCount(impact.connections)}${accountsClause(profile, impact)}. Alle Zugangsdaten werden sofort verworfen. Der Zugang bleibt bestehen; die Bibliotheken laufen erst wieder, wenn ihre Zugangsdaten neu eingetragen sind.${personsNotice(profile)}`,
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

  const typeLocked = (sourceType: string) =>
    typeStates.some((state) => state.sourceType === sourceType && state.locked)

  async function handleLock(profile: ConnectionProfileResponse) {
    const lock = !profile.locked
    const remaining =
      !lock && typeLocked(profile.sourceType)
        ? `Die Quellart „${displayNameOf(profile.sourceType)}“ bleibt gesperrt: Die Bibliotheken des Zugangs laufen erst weiter, wenn auch ihre Sperre aufgehoben ist.`
        : undefined
    if (!(await confirmLock(`den Zugang „${profile.name}“`, lock, remaining))) return
    try {
      await lockConnectionProfile(profile.id, lock)
      notify(
        lock
          ? `„${profile.name}“ ist gesperrt.`
          : `Die Sperre von „${profile.name}“ ist aufgehoben.`,
        'success',
      )
      reload()
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Die Sperre ließ sich nicht ändern.', 'error')
    }
  }

  async function handleTestSignIn(profile: ConnectionProfileResponse) {
    try {
      const result = await testConnectionProfileSignIn(profile.id)
      notify(result.message, result.success ? 'success' : 'error')
      reload()
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Der Anmeldetest ist fehlgeschlagen.', 'error')
    }
  }

  async function handleDelete(profile: ConnectionProfileResponse) {
    try {
      const impact = await getConnectionProfileImpact(profile.id)
      const confirmed = await confirmAction({
        question: `Zugang „${profile.name}“ löschen?`,
        consequence: `Betroffen: ${connectionCount(impact.connections)}${accountsClause(profile, impact)}; sie werden getrennt. Die Bibliotheken bleiben mit ihrem Inhalt und dem Hinweis „Zugang entfernt“ stehen und laufen nicht mehr, bis sie einem anderen Zugang zugeordnet sind.`,
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
                <TableCell>Verbundene Konten</TableCell>
                <TableCell>Davon abgelaufen</TableCell>
                <TableCell align="right">Aktionen</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {profiles.map((profile) => (
                <TableRow key={profile.id}>
                  <TableCell>
                    <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                      <span>{profile.name}</span>
                      {profile.locked && <Chip size="small" color="error" label="Gesperrt" />}
                      {typeLocked(profile.sourceType) && (
                        <Chip
                          size="small"
                          color="error"
                          variant="outlined"
                          label="Quellart gesperrt"
                        />
                      )}
                      {profile.signInRejected && (
                        <Chip size="small" color="error" label="Anmeldung abgelehnt" />
                      )}
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
                  <TableCell>{personCountLabel(profile.connectedAccountCount)}</TableCell>
                  <TableCell>
                    <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                      <span>{personCountLabel(profile.expiredConnectionCount)}</span>
                      {profile.expiredConnectionWarning && (
                        <Chip size="small" color="warning" label="Viele abgelaufen" />
                      )}
                    </Stack>
                  </TableCell>
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
                        color={profile.locked ? 'primary' : 'error'}
                        onClick={() => void handleLock(profile)}
                        aria-label={
                          profile.locked
                            ? `Sperre von ${profile.name} aufheben`
                            : `${profile.name} sperren`
                        }
                      >
                        {profile.locked ? 'Entsperren' : 'Sperren'}
                      </Button>
                      {signsInItself(profile) && (
                        <Button
                          size="small"
                          onClick={() => void handleTestSignIn(profile)}
                          aria-label={`Anmeldung von ${profile.name} testen`}
                        >
                          Anmeldung testen
                        </Button>
                      )}
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
        {!loading && profiles.length > 0 && (
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
            Verbundene Konten zählen die Konten von Personen auf einem Zugang, verbundene und
            abgelaufene. Kleine Zahlen erscheinen nur als „weniger als …“, damit keine Zahl auf
            einzelne Personen schließen lässt; „nicht ausgewiesen“ steht dort, wo schon die Zahl der
            abgelaufenen Konten das täte. „Viele abgelaufen“ erscheint, sobald die ausgewiesenen
            Zahlen den eingestellten Schwellenwert erreichen.
          </Typography>
        )}

        <Box sx={{ mt: 4 }}>
          <ConnectionProfileRequestSection
            reloadKey={reloadKey}
            displayNameOf={displayNameOf}
            onCreateProfile={(request) => setForm({ open: true, profile: null, request })}
          />
        </Box>

        <Box sx={{ mt: 4 }}>
          <ConnectorTypeLockSection
            states={typeStates}
            error={typeError}
            lockedProfileCount={(sourceType) =>
              profiles.filter((profile) => profile.sourceType === sourceType && profile.locked)
                .length
            }
            onChanged={reload}
          />
        </Box>

        <Box sx={{ mt: 4 }}>
          <PrivateStorageSection />
        </Box>

        <Box sx={{ mt: 4 }}>
          <ConnectionLogRetentionSection />
        </Box>

        <ConnectionProfileFormDialog
          key={form.profile?.id ?? form.request?.id ?? (form.open ? 'new-open' : 'new')}
          open={form.open}
          profile={form.profile}
          fromRequest={form.request ?? null}
          sourceTypes={sourceTypes}
          sourceTypesLoaded={sourceTypesLoaded}
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
