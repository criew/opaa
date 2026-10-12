import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Drawer from '@mui/material/Drawer'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import type { CapabilityOverviewResponse, ConnectionProfileResponse } from '../../../types/api'
import { notify } from '../../../stores/notificationStore'
import KeyValueList from '../../KeyValueList'
import MetaBadge from '../../MetaBadge'
import SectionHead from '../../SectionHead'
import StatusLine from '../../StatusLine'
import AccessBadge from '../capabilities/AccessBadge'
import AccessEditor from '../capabilities/AccessEditor'
import {
  accessBadgeLabel,
  currentAccess,
  objectPhrase,
  resultSentence,
  type Access,
} from '../capabilities/capabilityAccess'
import { applyAccess, confirmAccess } from '../capabilities/capabilityAccessChange'
import { AUTH_METHOD_LABELS, OWNERSHIP_LABELS } from './connectionProfileLabels'
import { admitsPersons, personCountLabel } from './profileChange'
import { formatDay, signsInItself, type ProfileAttention } from './profileState'

function PanelSection({ title, children }: { title: string; children: ReactNode }) {
  const id = useId()
  return (
    <Box component="section" aria-labelledby={id}>
      <SectionHead id={id} component="h3">
        {title}
      </SectionHead>
      {children}
    </Box>
  )
}

/** One emergency lever: its button and, beside it, what it does. */
function Lever({ action, children }: { action: ReactNode; children: ReactNode }) {
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: '200px 1fr' },
        columnGap: 2,
        rowGap: 0.5,
        alignItems: 'start',
        py: 1.25,
        '& + &': { borderTop: 1, borderColor: 'divider' },
      }}
    >
      <Box>{action}</Box>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', pt: { sm: 0.5 } }}>
        {children}
      </Typography>
    </Box>
  )
}

function defaultsLine(profile: ConnectionProfileResponse): string | null {
  const settings = profile.connectorSettings
  if (!settings) return null
  const pairs = Object.entries(settings).filter(([, value]) => value !== null && value !== '')
  return pairs.length === 0
    ? null
    : pairs.map(([key, value]) => `${key}: ${String(value)}`).join(', ')
}

export interface ProfilePanelActions {
  onEdit: (profile: ConnectionProfileResponse) => void
  onTestSignIn: (profile: ConnectionProfileResponse) => Promise<void>
  onLock: (profile: ConnectionProfileResponse) => Promise<void>
  onDisconnect: (profile: ConnectionProfileResponse) => Promise<void>
  /** Resolves true once the profile is gone, so the panel closes. */
  onDelete: (profile: ConnectionProfileResponse) => Promise<boolean>
  /** Reloads the overview of the Anlegerechte after a change of who may use the profile. */
  onAccessChanged: () => Promise<void>
}

interface ConnectionProfilePanelProps extends ProfilePanelActions {
  /** The profile to show; null keeps the panel closed. */
  profile: ConnectionProfileResponse | null
  sourceTypeName: string
  typeLocked: boolean
  attention: ProfileAttention | null
  /** The release of the profile; null while it is unknown. */
  accessEntry: CapabilityOverviewResponse | null
  accessError: string | null
  onRetryAccess: () => void
  onClose: () => void
}

/**
 * Everything about one profile: what needs doing first, then who may use it, its data, its
 * connections and the levers that stop or remove it. The overview only shows; changes happen
 * here, each with the question the page asked before.
 */
export default function ConnectionProfilePanel(props: ConnectionProfilePanelProps) {
  const { profile, onClose } = props
  // Held here so that Escape and the backdrop cannot close the panel while a change runs.
  const [busy, setBusy] = useState(false)
  return (
    <Drawer
      anchor="right"
      open={profile !== null}
      onClose={busy ? undefined : onClose}
      slotProps={{
        paper: {
          role: 'dialog',
          'aria-labelledby': 'connection-profile-panel-heading',
          sx: { width: { xs: '100%', sm: 560 }, display: 'flex', flexDirection: 'column' },
        },
      }}
    >
      {profile && (
        <PanelContent key={profile.id} {...props} profile={profile} busy={busy} setBusy={setBusy} />
      )}
    </Drawer>
  )
}

function PanelContent({
  profile,
  sourceTypeName,
  typeLocked,
  attention,
  accessEntry,
  accessError,
  onRetryAccess,
  onClose,
  onEdit,
  onTestSignIn,
  onLock,
  onDisconnect,
  onDelete,
  onAccessChanged,
  busy,
  setBusy,
}: ConnectionProfilePanelProps & {
  profile: ConnectionProfileResponse
  busy: boolean
  setBusy: (busy: boolean) => void
}) {
  const [editingAccess, setEditingAccess] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const errorRef = useRef<HTMLDivElement>(null)
  const accessSectionRef = useRef<HTMLDivElement>(null)

  // After a failed action the focus goes to the message (docs/design/accessibility.md, 2.7).
  useEffect(() => {
    if (error) errorRef.current?.focus()
  }, [error])

  const access = accessEntry ? currentAccess(accessEntry) : null
  const phrase = accessEntry ? objectPhrase(accessEntry) : ''

  async function run(action: () => Promise<unknown>) {
    setBusy(true)
    try {
      await action()
    } finally {
      setBusy(false)
    }
  }

  async function saveAccess(target: Access) {
    if (!accessEntry) return
    setError(null)
    if (!(await confirmAccess(accessEntry, target, phrase))) return
    setBusy(true)
    try {
      await applyAccess(accessEntry, target)
      notify(`Gespeichert: ${resultSentence(target, phrase)}`, 'success')
      setEditingAccess(false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Änderung konnte nicht gespeichert werden.')
    } finally {
      await onAccessChanged()
      setBusy(false)
    }
  }

  function openAccessEditor() {
    setEditingAccess(true)
    accessSectionRef.current?.scrollIntoView?.({ block: 'start' })
  }

  const fixAction = !attention ? null : attention.kind === 'NOT_RELEASED' ? (
    <Button size="small" variant="contained" onClick={openAccessEditor} disabled={busy}>
      Festlegen, wer ihn nutzen darf
    </Button>
  ) : attention.kind === 'MANY_EXPIRED' ? null : (
    <Button
      size="small"
      variant={attention.tone === 'error' ? 'contained' : 'outlined'}
      onClick={() => onEdit(profile)}
      disabled={busy}
    >
      {attention.kind === 'SIGN_IN_REJECTED' ? 'Zugang bearbeiten' : 'Neues Secret eintragen'}
    </Button>
  )

  const defaults = defaultsLine(profile)
  const persons = admitsPersons(profile.ownership)

  return (
    <>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 1,
          px: 3,
          pt: 2.5,
          pb: 2,
          borderBottom: 1,
          borderColor: 'divider',
        }}
      >
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography
            id="connection-profile-panel-heading"
            component="h2"
            sx={{ fontSize: 18, fontWeight: 600, overflowWrap: 'anywhere' }}
          >
            {profile.name}
          </Typography>
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, mt: 0.75 }}>
            <MetaBadge>{sourceTypeName}</MetaBadge>
            {profile.locked && <MetaBadge>Gesperrt</MetaBadge>}
            {typeLocked && <MetaBadge>Quellart gesperrt</MetaBadge>}
          </Box>
        </Box>
        <IconButton aria-label="Schließen" onClick={onClose} disabled={busy} sx={{ mt: -0.5 }}>
          <CloseIcon />
        </IconButton>
      </Box>

      <Stack spacing={3.5} sx={{ flex: 1, overflowY: 'auto', px: 3, py: 2.5 }}>
        {error && (
          <Alert ref={errorRef} tabIndex={-1} severity="error" onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        {attention && (
          <Box>
            <StatusLine
              component="h3"
              label="Handlungsbedarf"
              tone={attention.tone}
              headline={attention.label}
              detail={attention.detail}
            />
            {fixAction && <Box sx={{ pl: 2 }}>{fixAction}</Box>}
          </Box>
        )}

        <Box ref={accessSectionRef}>
          <PanelSection title="Wer darf diesen Zugang nutzen?">
            {accessError ? (
              <Alert
                severity="error"
                action={
                  <Button color="inherit" size="small" onClick={onRetryAccess}>
                    Erneut laden
                  </Button>
                }
              >
                {accessError}
              </Alert>
            ) : !accessEntry || !access ? (
              <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                Die Freigabe wird geladen …
              </Typography>
            ) : editingAccess ? (
              <AccessEditor
                key={accessEntry.scope ?? profile.id}
                entry={accessEntry}
                phrase={phrase}
                busy={busy}
                onCancel={() => setEditingAccess(false)}
                onSave={saveAccess}
              />
            ) : (
              <>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
                  <AccessBadge tone={access.level} label={accessBadgeLabel(access)} />
                  <Button size="small" onClick={openAccessEditor} disabled={busy}>
                    Ändern
                  </Button>
                </Box>
                <Typography sx={{ fontSize: 13, mt: 1 }}>
                  {resultSentence(access, phrase)}
                </Typography>
                <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
                  Wer ihn nutzen darf, kann damit Bibliotheken anlegen und das eigene Konto
                  verbinden. Bei einem Entzug laufen bestehende Bibliotheken weiter. Dieselbe
                  Einstellung steht unter Anlegerechte.
                </Typography>
              </>
            )}
          </PanelSection>
        </Box>

        <PanelSection title="Angaben">
          <KeyValueList
            entries={[
              { label: 'Quellart', value: sourceTypeName },
              { label: 'Server-Adresse', value: profile.serverUrl ?? '—' },
              {
                label: 'Anmeldung',
                value: `${AUTH_METHOD_LABELS[profile.authMethod]}, für ${OWNERSHIP_LABELS[profile.ownership]}`,
              },
              { label: 'Client-ID', value: profile.clientId ?? '', hidden: !profile.clientId },
              { label: 'Mandant', value: profile.tenant ?? '', hidden: !profile.tenant },
              { label: 'Scopes', value: profile.scopes ?? '', hidden: !profile.scopes },
              {
                label: 'Secret läuft ab',
                value: profile.clientSecretExpiresOn
                  ? formatDay(profile.clientSecretExpiresOn)
                  : '',
                hidden: !profile.clientSecretExpiresOn,
              },
              { label: 'Vorgaben', value: defaults ?? '', hidden: defaults === null },
            ]}
          />
          <Button
            size="small"
            variant="outlined"
            sx={{ mt: 1.5 }}
            onClick={() => onEdit(profile)}
            disabled={busy}
            aria-label={`${profile.name} bearbeiten`}
          >
            Bearbeiten
          </Button>
        </PanelSection>

        <PanelSection title="Nutzung">
          <KeyValueList
            entries={[
              { label: 'Verbindungen', value: String(profile.connectionCount) },
              {
                label: 'Verbundene Konten',
                value: personCountLabel(profile.connectedAccountCount),
                hidden: !persons,
              },
              {
                label: 'Davon abgelaufen',
                value: personCountLabel(profile.expiredConnectionCount),
                hidden: !persons,
              },
            ]}
          />
          {persons && (
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
              Verbundene Konten zählen die Konten von Personen auf diesem Zugang, verbundene und
              abgelaufene. Kleine Zahlen erscheinen nur als „weniger als …“, damit keine Zahl auf
              einzelne Personen schließen lässt; „nicht ausgewiesen“ steht dort, wo schon die Zahl
              der abgelaufenen Konten das täte. „Viele Konten abgelaufen“ erscheint als
              Handlungsbedarf, sobald die ausgewiesenen Zahlen den eingestellten Schwellenwert
              erreichen.
            </Typography>
          )}
        </PanelSection>

        <PanelSection title="Eingriffe">
          {signsInItself(profile) && (
            <Lever
              action={
                <Button
                  size="small"
                  variant="outlined"
                  disabled={busy}
                  onClick={() => void run(() => onTestSignIn(profile))}
                  aria-label={`Anmeldung von ${profile.name} testen`}
                >
                  Anmeldung testen
                </Button>
              }
            >
              Meldet sich einmal mit der Registrierung des Zugangs beim Anbieter an.
            </Lever>
          )}
          <Lever
            action={
              <Button
                size="small"
                variant="outlined"
                color={profile.locked ? 'primary' : 'error'}
                disabled={busy}
                onClick={() => void run(() => onLock(profile))}
                aria-label={
                  profile.locked ? `Sperre von ${profile.name} aufheben` : `${profile.name} sperren`
                }
              >
                {profile.locked ? 'Entsperren' : 'Sperren'}
              </Button>
            }
          >
            {profile.locked
              ? 'Die Bibliotheken laufen nach dem Entsperren ohne Neueinrichtung weiter.'
              : 'Keine neuen Bibliotheken, die Läufe stoppen; der Inhalt bleibt durchsuchbar.'}
          </Lever>
          <Lever
            action={
              <Button
                size="small"
                variant="outlined"
                color="warning"
                disabled={busy}
                onClick={() => void run(() => onDisconnect(profile))}
                aria-label={`Alle Verbindungen von ${profile.name} trennen`}
              >
                Alle Verbindungen trennen
              </Button>
            }
          >
            Notabschaltung: verwirft sofort alle Zugangsdaten auf diesem Zugang. Der Zugang bleibt
            bestehen.
          </Lever>
          <Lever
            action={
              <Button
                size="small"
                variant="outlined"
                color="error"
                disabled={busy}
                onClick={() =>
                  void run(async () => {
                    if (await onDelete(profile)) onClose()
                  })
                }
                aria-label={`${profile.name} löschen`}
              >
                Zugang löschen
              </Button>
            }
          >
            Trennt alle Verbindungen. Die Bibliotheken bleiben mit Inhalt stehen und laufen erst
            wieder auf einem anderen Zugang.
          </Lever>
        </PanelSection>
      </Stack>
    </>
  )
}
