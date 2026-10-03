import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type {
  ConnectionAuthMethod,
  ConnectionOwnership,
  ConnectionProfileResponse,
  SourceTypeDescriptor,
} from '../../../types/api'
import ChoiceTileGroup from '../../choice/ChoiceTileGroup'
import SourceTypeIcon from '../../library/sourceTypeIcon'
import {
  createConnectionProfile,
  getConnectionProfileImpact,
  needsConfirmation,
  updateConnectionProfile,
} from '../../../services/connectionProfileApi'
import { confirmAction } from '../../../stores/confirmStore'
import { AUTH_METHOD_LABELS, OWNERSHIP_LABELS } from './connectionProfileLabels'

const WITH_REGISTRATION: ConnectionAuthMethod[] = [
  'OAUTH',
  'CLIENT_CREDENTIALS',
  'SERVICE_ACCOUNT_KEY',
]
const WITH_SCOPES: ConnectionAuthMethod[] = ['OAUTH', 'CLIENT_CREDENTIALS']

interface Draft {
  name: string
  serverUrl: string
  authMethod: ConnectionAuthMethod | ''
  ownership: ConnectionOwnership
  clientId: string
  tenant: string
  scopes: string
  clientSecretExpiresOn: string
}

function draftFrom(profile: ConnectionProfileResponse | null): Draft {
  return {
    name: profile?.name ?? '',
    serverUrl: profile?.serverUrl ?? '',
    authMethod: profile?.authMethod ?? '',
    ownership: profile?.ownership ?? 'LIBRARY',
    clientId: profile?.clientId ?? '',
    tenant: profile?.tenant ?? '',
    scopes: profile?.scopes ?? '',
    clientSecretExpiresOn: profile?.clientSecretExpiresOn ?? '',
  }
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

interface ConnectionProfileFormDialogProps {
  open: boolean
  /** The profile being edited; `null` creates a new one. */
  profile: ConnectionProfileResponse | null
  sourceTypes: SourceTypeDescriptor[]
  onClose: () => void
  onSaved: (profile: ConnectionProfileResponse) => void
}

/**
 * Anlegen und Bearbeiten eines Zugangs (#2160). Beim Anlegen wählt die Systemverwaltung zuerst
 * die Quellart als Kachel; Quellarten ohne Zugänge bleiben mit Grund sichtbar. Eine Änderung, die
 * Geheimnisse verwirft, fragt vorher mit der Zahl der betroffenen Verbindungen nach.
 */
export default function ConnectionProfileFormDialog({
  open,
  profile,
  sourceTypes,
  onClose,
  onSaved,
}: ConnectionProfileFormDialogProps) {
  const [sourceType, setSourceType] = useState<string | null>(profile?.sourceType ?? null)
  const [draft, setDraft] = useState<Draft>(() => draftFrom(profile))
  const [secret, setSecret] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const isEdit = profile !== null
  const descriptor = sourceTypes.find((type) => type.type === sourceType) ?? null
  const methods = descriptor?.authMethods ?? []
  const method = draft.authMethod === '' ? null : draft.authMethod
  const usesRegistration = method !== null && WITH_REGISTRATION.includes(method)
  const usesScopes = method !== null && WITH_SCOPES.includes(method)
  const connectorTypes = sourceTypes.filter((type) => !type.uploads)

  function close() {
    if (!submitting) onClose()
  }

  function fields() {
    return {
      name: draft.name.trim(),
      serverUrl: draft.serverUrl.trim(),
      authMethod: method as ConnectionAuthMethod,
      ownership: draft.ownership,
      clientId: usesRegistration ? blankToNull(draft.clientId) : null,
      tenant: usesRegistration ? blankToNull(draft.tenant) : null,
      clientSecretExpiresOn: usesRegistration ? blankToNull(draft.clientSecretExpiresOn) : null,
      scopes: usesScopes ? blankToNull(draft.scopes) : null,
      clientSecret: usesRegistration && secret.trim() !== '' ? secret.trim() : undefined,
    }
  }

  async function save(confirmDiscard: boolean): Promise<ConnectionProfileResponse> {
    if (profile) {
      return updateConnectionProfile(profile.id, { ...fields(), confirmDiscard })
    }
    return createConnectionProfile({ ...fields(), sourceType: sourceType as string })
  }

  async function handleSubmit() {
    if (
      sourceType === null ||
      draft.name.trim() === '' ||
      draft.serverUrl.trim() === '' ||
      !method
    ) {
      setError('Quellart, Name, Server-Adresse und Anmeldeart sind erforderlich.')
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      let saved: ConnectionProfileResponse
      try {
        saved = await save(false)
      } catch (err) {
        if (!profile || !needsConfirmation(err)) throw err
        const impact = await getConnectionProfileImpact(profile.id)
        const confirmed = await confirmAction({
          question: `Zugangsdaten von „${profile.name}“ verwerfen?`,
          consequence: `Die Änderung betrifft ${impact.connections} ${
            impact.connections === 1 ? 'Verbindung' : 'Verbindungen'
          } und ${impact.libraries} ${
            impact.libraries === 1 ? 'Bibliothek' : 'Bibliotheken'
          }. Alle hinterlegten Geheimnisse werden verworfen und müssen neu eingetragen werden.`,
          confirmLabel: 'Verwerfen und speichern',
          tone: 'danger',
        })
        if (!confirmed) return
        saved = await save(true)
      }
      onSaved(saved)
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Der Zugang konnte nicht gespeichert werden.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onClose={close} maxWidth="sm" fullWidth>
      <DialogTitle>{isEdit ? `„${profile.name}“ bearbeiten` : 'Zugang anlegen'}</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Stack spacing={2} sx={{ mt: 1 }}>
          {!isEdit && (
            <>
              <Typography variant="body2" id="connection-profile-source-type">
                Für welche Quellart gilt der Zugang?
              </Typography>
              <ChoiceTileGroup<string>
                aria-labelledby="connection-profile-source-type"
                value={sourceType}
                onChange={(next) => {
                  setSourceType(next)
                  setDraft({ ...draft, authMethod: '' })
                }}
                tiles={connectorTypes.map((type) => ({
                  value: type.type,
                  label: type.displayName,
                  icon: <SourceTypeIcon sourceType={type.type} fontSize={22} />,
                  disabledReason:
                    type.profileSupport === 'FORBIDDEN'
                      ? 'Diese Quellart kennt keine Zugänge.'
                      : null,
                }))}
              />
            </>
          )}
          {descriptor && (
            <>
              <TextField
                label="Name"
                required
                size="small"
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                helperText="Erscheint in der Auswahl, z. B. „Zugang Nextcloud intern“."
              />
              <TextField
                label="Server-Adresse"
                required
                size="small"
                value={draft.serverUrl}
                onChange={(e) => setDraft({ ...draft, serverUrl: e.target.value })}
                helperText="Das einzige Ziel der Zugangsdaten. Eine Änderung verwirft alle Geheimnisse des Zugangs."
              />
              <TextField
                select
                label="Anmeldeart"
                required
                size="small"
                value={draft.authMethod}
                onChange={(e) =>
                  setDraft({ ...draft, authMethod: e.target.value as ConnectionAuthMethod })
                }
              >
                {methods.map((m) => (
                  <MenuItem key={m} value={m}>
                    {AUTH_METHOD_LABELS[m]}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                select
                label="Besitzart"
                required
                size="small"
                value={draft.ownership}
                onChange={(e) =>
                  setDraft({ ...draft, ownership: e.target.value as ConnectionOwnership })
                }
                helperText="Welche Verbindungen auf diesem Zugang entstehen dürfen."
              >
                {(Object.keys(OWNERSHIP_LABELS) as ConnectionOwnership[]).map((o) => (
                  <MenuItem key={o} value={o}>
                    {OWNERSHIP_LABELS[o]}
                  </MenuItem>
                ))}
              </TextField>
              {usesRegistration && (
                <>
                  <TextField
                    label="Client-ID"
                    required
                    size="small"
                    value={draft.clientId}
                    onChange={(e) => setDraft({ ...draft, clientId: e.target.value })}
                  />
                  <TextField
                    label="Client-Secret"
                    type="password"
                    size="small"
                    autoComplete="new-password"
                    value={secret}
                    onChange={(e) => setSecret(e.target.value)}
                    helperText={
                      profile?.clientSecretSet
                        ? 'Ein Secret ist hinterlegt. Leer lassen, um es zu behalten.'
                        : 'Wird verschlüsselt gespeichert und nie wieder angezeigt.'
                    }
                  />
                  <TextField
                    label="Ablaufdatum des Secrets"
                    type="date"
                    size="small"
                    value={draft.clientSecretExpiresOn}
                    onChange={(e) => setDraft({ ...draft, clientSecretExpiresOn: e.target.value })}
                    slotProps={{ inputLabel: { shrink: true } }}
                    helperText="OPAA warnt 14 Tage vorher."
                  />
                  <TextField
                    label="Mandant"
                    size="small"
                    value={draft.tenant}
                    onChange={(e) => setDraft({ ...draft, tenant: e.target.value })}
                  />
                </>
              )}
              {usesScopes && (
                <TextField
                  label="Scopes"
                  size="small"
                  value={draft.scopes}
                  onChange={(e) => setDraft({ ...draft, scopes: e.target.value })}
                  helperText="Durch Leerzeichen getrennt, z. B. „Files.Read offline_access“."
                />
              )}
            </>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close} disabled={submitting}>
          Abbrechen
        </Button>
        <Button variant="contained" onClick={() => void handleSubmit()} disabled={submitting}>
          {isEdit ? 'Speichern' : 'Anlegen'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
