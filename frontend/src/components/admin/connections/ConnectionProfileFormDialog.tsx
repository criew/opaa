import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type {
  ConnectionAuthMethod,
  ConnectionOwnership,
  ConnectionProfileImpactResponse,
  ConnectionProfileRequestResponse,
  ConnectionProfileResponse,
  ConnectionProfileUpdateRequest,
  ProfileDefaultKey,
  ProfileDefaultKind,
  SourceTypeDescriptor,
  SourceTypeSignIn,
} from '../../../types/api'
import BusyButton from '../../a11y/BusyButton'
import ChoiceTileGroup from '../../choice/ChoiceTileGroup'
import SourceTypeIcon from '../../library/sourceTypeIcon'
import {
  changeRejected,
  createConnectionProfile,
  getConnectionRedirectUri,
  needsConfirmation,
  previewConnectionProfileChange,
  updateConnectionProfile,
} from '../../../services/connectionProfileApi'
import { confirmAction } from '../../../stores/confirmStore'
import { AUTH_METHOD_LABELS, OWNERSHIP_LABELS } from './connectionProfileLabels'
import ProfileChangePreview from './ProfileChangePreview'
import ServiceAccountKeyField from './ServiceAccountKeyField'
import { reachesLibraries as changeReachesLibraries } from './profileChange'

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
  authorizationEndpoint: string
  tokenEndpoint: string
  revocationEndpoint: string
  clientSecretExpiresOn: string
  sourceProxy: string
  sourceInsecureSsl: boolean
  /** One entry per declared profile default; an empty string sets nothing. */
  defaults: Record<string, string>
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
    authorizationEndpoint: profile?.authorizationEndpoint ?? '',
    tokenEndpoint: profile?.tokenEndpoint ?? '',
    revocationEndpoint: profile?.revocationEndpoint ?? '',
    clientSecretExpiresOn: profile?.clientSecretExpiresOn ?? '',
    sourceProxy: profile?.sourceProxy ?? '',
    sourceInsecureSsl: profile?.sourceInsecureSsl ?? false,
    defaults: Object.fromEntries(
      Object.entries(profile?.connectorSettings ?? {}).map(([key, value]) => [
        key,
        value === null || value === undefined ? '' : String(value),
      ]),
    ),
  }
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

/** The declared defaults the draft sets, typed by kind; `null` when it sets none. */
function connectorDefaults(
  keys: ProfileDefaultKey[],
  values: Record<string, string>,
): Record<string, string | boolean> | null {
  const result: Record<string, string | boolean> = {}
  for (const key of keys) {
    const value = (values[key.key] ?? '').trim()
    if (value === '') continue
    result[key.key] = key.kind === 'BOOLEAN' ? value === 'true' : value
  }
  return Object.keys(result).length === 0 ? null : result
}

/** The ownerships a profile choosing `signIn` may take; all of them while it is unknown. */
function ownershipsFor(signIn: SourceTypeSignIn | undefined): ConnectionOwnership[] {
  if (!signIn) return Object.keys(OWNERSHIP_LABELS) as ConnectionOwnership[]
  const allowed: ConnectionOwnership[] = [...signIn.ownerships]
  if (allowed.includes('LIBRARY') && allowed.includes('PERSON')) allowed.push('BOTH')
  return allowed
}

function addressHint(schemes: string[]): string {
  return `Beginnt mit ${schemes.map((scheme) => `${scheme}://`).join(' oder ')}. Das einzige Ziel der Zugangsdaten. Eine Änderung verwirft alle Geheimnisse des Zugangs.`
}

/** The endpoint fields a sign-in leaves to the profile, in the order of the form. */
const ENDPOINT_FIELDS = [
  {
    key: 'authorizationEndpoint',
    flag: 'authorization',
    label: 'Autorisierungs-Endpunkt',
    hint: 'Wohin OPAA die Person zur Zustimmung schickt.',
  },
  {
    key: 'tokenEndpoint',
    flag: 'token',
    label: 'Token-Endpunkt',
    hint: 'Wo OPAA Code und Refresh-Token gegen ein Zugriffstoken tauscht.',
  },
  {
    key: 'revocationEndpoint',
    flag: 'revocation',
    label: 'Widerrufs-Endpunkt',
    hint: 'Wo OPAA ein Token beim Trennen widerruft (RFC 7009).',
  },
] as const

interface ConnectionProfileFormDialogProps {
  open: boolean
  /** The profile being edited; `null` creates a new one. */
  profile: ConnectionProfileResponse | null
  /** A request the new profile serves: presets type and address, resolved on saving. */
  fromRequest?: ConnectionProfileRequestResponse | null
  sourceTypes: SourceTypeDescriptor[]
  /** Whether the answer for `sourceTypes` is in, successful or not. */
  sourceTypesLoaded: boolean
  onClose: () => void
  onSaved: (profile: ConnectionProfileResponse) => void
}

/**
 * Creates and edits a connection profile. Creating first asks for the source type as a tile; types
 * without profiles stay visible with their reason. Sign-ins, ownerships, the address field and one
 * field per declared default follow the type's description; a fixed address asks for nothing. An
 * edit sends every field back, so it waits for that description - without it the defaults would be
 * lost. Created for a request, type and address start from it and saving resolves the request; a
 * new profile is created without a preview. An edit that may reach the libraries on the profile
 * shows its preview first - how many, which ones their connector refuses, what saving discards -
 * and saves only on a second click; a change that discards anything asks once more with the
 * server's own question.
 */
export default function ConnectionProfileFormDialog({
  open,
  profile,
  fromRequest = null,
  sourceTypes,
  sourceTypesLoaded,
  onClose,
  onSaved,
}: ConnectionProfileFormDialogProps) {
  const [sourceType, setSourceType] = useState<string | null>(
    profile?.sourceType ?? fromRequest?.sourceType ?? null,
  )
  const [draft, setDraft] = useState<Draft>(() =>
    fromRequest && !profile
      ? { ...draftFrom(null), serverUrl: fromRequest.serverUrl }
      : draftFrom(profile),
  )
  const [secret, setSecret] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [preview, setPreview] = useState<{
    token: string
    impact: ConnectionProfileImpactResponse
  } | null>(null)

  // undefined while asked, null where the server has no public address
  const [redirect, setRedirect] = useState<{ uri: string | null } | 'failed' | undefined>()

  const isEdit = profile !== null
  const descriptor = sourceTypes.find((type) => type.type === sourceType) ?? null
  // While editing, the profile's own method stays offered even if the list has not loaded yet.
  const methods: ConnectionAuthMethod[] =
    descriptor?.signIns.map((signIn) => signIn.method) ?? (profile ? [profile.authMethod] : [])
  const method = draft.authMethod === '' ? null : draft.authMethod
  const signIn = descriptor?.signIns.find((offered) => offered.method === method)
  const ownerships = ownershipsFor(signIn)
  // the endpoints the connector leaves to the profile, each required then
  const endpointFields = ENDPOINT_FIELDS.filter((field) => signIn?.profileEndpoints?.[field.flag])
  const defaultKeys = descriptor?.profileDefaults ?? []
  const fixedAddress = descriptor?.serverAddress.fixed ?? null
  const schemes = descriptor?.serverAddress.schemes ?? ['https', 'http']
  // proxy and certificate check reach only an http(s) server; another scheme (smb) takes neither
  const takesTransport =
    fixedAddress !== null
      ? /^https?:/i.test(fixedAddress)
      : schemes.some((scheme) => scheme === 'http' || scheme === 'https')
  const usesRegistration = method !== null && WITH_REGISTRATION.includes(method)
  const usesScopes = method !== null && WITH_SCOPES.includes(method)
  // a service account key names its client id itself
  const usesKey = method === 'SERVICE_ACCOUNT_KEY'
  const connectorTypes = sourceTypes.filter((type) => !type.uploads)
  const showFields = isEdit || (descriptor !== null && descriptor.profileSupport !== 'FORBIDDEN')
  const descriptorMissing = isEdit && descriptor === null
  const complete =
    showFields &&
    !descriptorMissing &&
    draft.name.trim() !== '' &&
    (fixedAddress !== null || draft.serverUrl.trim() !== '') &&
    method !== null &&
    ownerships.includes(draft.ownership) &&
    (!usesRegistration || usesKey || draft.clientId.trim() !== '') &&
    endpointFields.every((field) => draft[field.key].trim() !== '')

  const asksRedirect = method === 'OAUTH' && redirect === undefined
  useEffect(() => {
    if (!asksRedirect) return
    let active = true
    getConnectionRedirectUri()
      .then((uri) => {
        if (active) setRedirect({ uri })
      })
      .catch(() => {
        if (active) setRedirect('failed')
      })
    return () => {
      active = false
    }
  }, [asksRedirect])

  function close() {
    if (!submitting) onClose()
  }

  function fields() {
    return {
      name: draft.name.trim(),
      serverUrl: fixedAddress ?? draft.serverUrl.trim(),
      authMethod: method as ConnectionAuthMethod,
      ownership: draft.ownership,
      // the server takes a key's client id from the key; the stored one keeps the preview honest
      clientId: usesKey
        ? (profile?.clientId ?? null)
        : usesRegistration
          ? blankToNull(draft.clientId)
          : null,
      tenant: usesRegistration && !usesKey ? blankToNull(draft.tenant) : null,
      clientSecretExpiresOn: usesRegistration ? blankToNull(draft.clientSecretExpiresOn) : null,
      scopes: usesScopes ? blankToNull(draft.scopes) : null,
      ...Object.fromEntries(
        ENDPOINT_FIELDS.map((field) => [
          field.key,
          endpointFields.includes(field) ? blankToNull(draft[field.key]) : null,
        ]),
      ),
      clientSecret: usesRegistration && secret.trim() !== '' ? secret.trim() : undefined,
      connectorSettings: connectorDefaults(defaultKeys, draft.defaults),
      sourceProxy: takesTransport ? blankToNull(draft.sourceProxy) : null,
      sourceInsecureSsl: takesTransport && draft.sourceInsecureSsl,
    }
  }

  async function save(confirmDiscard: boolean): Promise<ConnectionProfileResponse> {
    if (profile) {
      return updateConnectionProfile(profile.id, { ...fields(), confirmDiscard })
    }
    return createConnectionProfile({
      ...fields(),
      sourceType: sourceType as string,
      ...(fromRequest ? { fulfillsRequestId: fromRequest.id } : {}),
    })
  }

  const update: ConnectionProfileUpdateRequest | null = complete && profile ? fields() : null
  const reachesLibraries =
    profile !== null &&
    update !== null &&
    profile.connectionCount > 0 &&
    changeReachesLibraries(profile, update)
  const token = JSON.stringify(update)
  const shownPreview = preview?.token === token ? preview.impact : null
  const refused = shownPreview !== null && shownPreview.rejectedLibraries > 0

  async function loadPreview(): Promise<ConnectionProfileImpactResponse | null> {
    if (!profile || !update) return null
    const impact = await previewConnectionProfileChange(profile.id, update)
    setPreview({ token, impact })
    return impact
  }

  async function handleSubmit() {
    if (!complete) return
    setError(null)
    setSubmitting(true)
    try {
      if (reachesLibraries && shownPreview === null) {
        await loadPreview()
        return
      }
      let saved: ConnectionProfileResponse
      try {
        saved = await save(false)
      } catch (err) {
        if (!profile || !needsConfirmation(err)) throw err
        // the server's question names what is discarded - the same text the preview quotes
        const confirmed = await confirmAction({
          question: `Änderung an „${profile.name}“ bestätigen?`,
          consequence:
            err instanceof Error
              ? err.message
              : 'Die Änderung verwirft Zugangsdaten oder Abgleichstände.',
          confirmLabel: 'Bestätigen und speichern',
          tone: 'danger',
        })
        if (!confirmed) return
        saved = await save(true)
      }
      onSaved(saved)
      onClose()
    } catch (err) {
      if (changeRejected(err)) {
        setError(
          `Nichts wurde gespeichert: ${err instanceof Error ? err.message : 'Der Konnektor lehnt die Änderung ab.'} Die Gründe je Bibliothek stehen in der Vorschau.`,
        )
        await loadPreview().catch(() => null)
      } else {
        setError(err instanceof Error ? err.message : 'Der Zugang konnte nicht gespeichert werden.')
      }
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
        {descriptorMissing && !sourceTypesLoaded && (
          <Typography variant="body2" sx={{ color: 'text.secondary', mb: 2 }}>
            Die Angaben der Quellart werden geladen …
          </Typography>
        )}
        {descriptorMissing && sourceTypesLoaded && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            Die Angaben der Quellart liegen nicht vor. Ohne sie gingen die Vorgaben des Zugangs beim
            Speichern verloren. Bitte die Seite neu laden und erneut bearbeiten.
          </Alert>
        )}
        {fromRequest && !isEdit && (
          <Alert severity="info" sx={{ mb: 2 }} data-testid="connection-profile-from-request">
            Für den Zugangswunsch von {fromRequest.requestedByName}. Beim Speichern wird der Wunsch
            erledigt und {fromRequest.requestedByName} benachrichtigt.
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
                  setDraft({ ...draft, authMethod: '', defaults: {} })
                }}
                tiles={connectorTypes.map((type) => ({
                  value: type.type,
                  label: type.displayName,
                  icon: <SourceTypeIcon sourceType={type.type} fontSize={22} />,
                  disabledReason:
                    type.profileSupport === 'FORBIDDEN'
                      ? 'Für diese Quellart sind in dieser Version keine Zugänge möglich.'
                      : fromRequest && fromRequest.sourceType !== type.type
                        ? 'Der Zugangswunsch gilt für eine andere Quellart.'
                        : null,
                }))}
              />
            </>
          )}
          {showFields && (
            <>
              <TextField
                label="Name"
                required
                size="small"
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                helperText="Erscheint in der Auswahl, z. B. „Zugang Nextcloud intern“."
              />
              {fixedAddress === null ? (
                <TextField
                  label="Server-Adresse"
                  required
                  size="small"
                  value={draft.serverUrl}
                  onChange={(e) => setDraft({ ...draft, serverUrl: e.target.value })}
                  helperText={addressHint(schemes)}
                />
              ) : (
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  Die Quellart hat eine feste Adresse: {fixedAddress}
                </Typography>
              )}
              <TextField
                select
                label="Anmeldeart"
                required
                size="small"
                value={draft.authMethod}
                onChange={(e) => {
                  const next = e.target.value as ConnectionAuthMethod
                  const allowed = ownershipsFor(
                    descriptor?.signIns.find((signIn) => signIn.method === next),
                  )
                  setDraft({
                    ...draft,
                    authMethod: next,
                    ownership: allowed.includes(draft.ownership) ? draft.ownership : allowed[0],
                  })
                }}
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
                {ownerships.map((o) => (
                  <MenuItem key={o} value={o}>
                    {OWNERSHIP_LABELS[o]}
                  </MenuItem>
                ))}
              </TextField>
              {usesKey && (
                <ServiceAccountKeyField
                  idPrefix="connection-profile"
                  storedAccount={profile?.clientId ?? null}
                  keySet={profile?.clientSecretSet ?? false}
                  onChange={setSecret}
                />
              )}
              {usesRegistration && !usesKey && (
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
                  helperText={
                    signIn?.defaultScopes
                      ? `Durch Leerzeichen getrennt. Leer lassen für die Vorgabe der Quellart: „${signIn.defaultScopes}“.`
                      : 'Durch Leerzeichen getrennt, z. B. „Files.Read offline_access“.'
                  }
                />
              )}
              {endpointFields.map((field) => (
                <TextField
                  key={field.key}
                  label={field.label}
                  required
                  size="small"
                  value={draft[field.key]}
                  onChange={(e) => setDraft({ ...draft, [field.key]: e.target.value })}
                  placeholder="https://"
                  autoComplete="off"
                  helperText={`${field.hint} Beginnt mit https:// oder http://; eine Änderung verlangt neue Zustimmungen.`}
                  slotProps={{ htmlInput: { maxLength: 2000 } }}
                />
              ))}
              {method === 'OAUTH' && redirect === 'failed' && (
                <Alert severity="warning" data-testid="connection-profile-redirect-uri">
                  Die Rücksprungadresse ließ sich nicht laden. Sie lautet
                  {' {OPAA_PUBLIC_BASE_URL}/connections/callback'}.
                </Alert>
              )}
              {method === 'OAUTH' && redirect !== undefined && redirect !== 'failed' && (
                <>
                  {redirect.uri ? (
                    <Typography
                      variant="body2"
                      sx={{ color: 'text.secondary', wordBreak: 'break-all' }}
                      data-testid="connection-profile-redirect-uri"
                    >
                      Rücksprungadresse für die App-Registrierung beim Anbieter: {redirect.uri}
                    </Typography>
                  ) : (
                    <Alert severity="warning" data-testid="connection-profile-redirect-uri">
                      Die öffentliche Adresse von OPAA (OPAA_PUBLIC_BASE_URL) ist nicht gesetzt.
                      Ohne sie gibt es keine Rücksprungadresse, und über diesen Zugang lässt sich
                      kein Konto beim Anbieter verbinden. Zuständig ist der Betrieb der
                      Installation.
                    </Alert>
                  )}
                </>
              )}
              {takesTransport && (
                <>
                  <TextField
                    label="Proxy (optional)"
                    size="small"
                    value={draft.sourceProxy}
                    onChange={(e) => setDraft({ ...draft, sourceProxy: e.target.value })}
                    placeholder="proxy.example.com:8080"
                    autoComplete="off"
                    helperText="Gilt für jede Bibliothek auf diesem Zugang; die Bibliothek setzt keinen eigenen."
                    slotProps={{ htmlInput: { maxLength: 255 } }}
                  />
                  <FormControlLabel
                    control={
                      <Switch
                        checked={draft.sourceInsecureSsl}
                        onChange={(e) =>
                          setDraft({ ...draft, sourceInsecureSsl: e.target.checked })
                        }
                      />
                    }
                    label="Zertifikatsprüfung aussetzen"
                  />
                </>
              )}
              {defaultKeys.length > 0 && (
                <>
                  <Typography variant="subtitle2" component="h3">
                    Vorgaben für jede Bibliothek
                  </Typography>
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    Optional. Eine Vorgabe gilt für jede Bibliothek auf diesem Zugang und ersetzt
                    deren eigene Einstellung.
                  </Typography>
                  {defaultKeys.map((key) => (
                    <DefaultField
                      key={key.key}
                      defaultKey={key}
                      value={draft.defaults[key.key] ?? ''}
                      onChange={(value) =>
                        setDraft({ ...draft, defaults: { ...draft.defaults, [key.key]: value } })
                      }
                    />
                  ))}
                </>
              )}
            </>
          )}
          {shownPreview && profile && (
            <ProfileChangePreview impact={shownPreview} ownership={profile.ownership} />
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close} disabled={submitting}>
          Abbrechen
        </Button>
        <BusyButton
          variant="contained"
          onClick={() => void handleSubmit()}
          disabled={!complete || refused}
          busy={submitting}
          busyAnnouncement={
            reachesLibraries && shownPreview === null
              ? 'Auswirkungen werden ermittelt'
              : 'Zugang wird gespeichert'
          }
        >
          {!isEdit ? 'Anlegen' : reachesLibraries && shownPreview === null ? 'Weiter' : 'Speichern'}
        </BusyButton>
      </DialogActions>
    </Dialog>
  )
}

interface DefaultFieldProps {
  defaultKey: ProfileDefaultKey
  /** The value as text; `''` sets nothing, a yes/no is `'true'` or `'false'`. */
  value: string
  onChange: (value: string) => void
}

/** The options of a default chosen from a list: yes/no or the declared choices. */
function optionsOf(defaultKey: ProfileDefaultKey): Array<{ value: string; label: string }> | null {
  const kind: ProfileDefaultKind = defaultKey.kind
  switch (kind) {
    case 'TEXT':
      return null
    case 'BOOLEAN':
      return [
        { value: 'true', label: 'Ja' },
        { value: 'false', label: 'Nein' },
      ]
    case 'CHOICE':
      return defaultKey.choices.map((choice) => ({ value: choice, label: choice }))
    default: {
      const unknown: never = kind
      throw new Error(`Unknown profile default kind ${String(unknown)}`)
    }
  }
}

/** One field per declared default: text, yes/no or a choice, each with "Keine Vorgabe". */
function DefaultField({ defaultKey, value, onChange }: DefaultFieldProps) {
  const options = optionsOf(defaultKey)
  if (options === null) {
    return (
      <TextField
        label={defaultKey.label}
        size="small"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        helperText="Leer lassen, um nichts vorzugeben."
      />
    )
  }
  return (
    <TextField
      select
      label={defaultKey.label}
      size="small"
      value={value}
      onChange={(e) => onChange(e.target.value)}
    >
      <MenuItem value="">
        <em>Keine Vorgabe</em>
      </MenuItem>
      {options.map((option) => (
        <MenuItem key={option.value} value={option.value}>
          {option.label}
        </MenuItem>
      ))}
    </TextField>
  )
}
