import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Divider from '@mui/material/Divider'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { OidcProviderRequest, OidcProviderResponse } from '../../types/api'
import { testOidcProvider } from '../../services/api'
import { useOidcProviderStore } from '../../stores/oidcProviderStore'

export const ISSUER_HELP_TEXT =
  'Die Issuer-URI, wie der Anbieter sie in seinen Tokens prägt (bei Keycloak die Realm-Adresse). ' +
  'Sie ist zugleich die Adresse, an der der Browser die Anmeldung startet.'
export const JWK_SET_HELP_TEXT =
  'Optional. Die Backend-seitige Adresse des JWK-Sets, wenn das Backend den Anbieter unter einer ' +
  'anderen Adresse erreicht als der Browser (Docker Compose: „keycloak“ statt „localhost“). Der ' +
  'Vertrauensanker jedes Kontos dieses Anbieters – nur mit Bedacht ändern.'
export const ROLES_CLAIM_CONFIRMATION =
  'Mit einem Rollen-Claim ist der Identitätsanbieter für die Systemrollen SYSTEM_ADMIN und AUDITOR ' +
  'führend: Rollen werden bei jeder Anmeldung aus dem Token übernommen, die manuelle Rollenvergabe ' +
  'für Konten dieses Anbieters ist gesperrt. Der letzte Systemverwalter bleibt geschützt. Möchten ' +
  'Sie den Rollen-Claim setzen?'
export const ROLES_CLAIM_NO_VALUES_WARNING =
  'Ohne Rollenwerte entzieht der Anbieter allen seinen Konten SYSTEM_ADMIN und AUDITOR.'
const REQUIRED_FIELDS_HINT = 'Anzeigename, Issuer-URI und Client-ID sind erforderlich.'

interface OidcProviderDraft {
  displayName: string
  issuerUri: string
  clientId: string
  jwkSetUri: string
  emailClaim: string
  displayNameClaim: string
  rolesClaim: string
  systemAdminRole: string
  auditorRole: string
  groupsClaim: string
}

function emptyDraft(): OidcProviderDraft {
  return {
    displayName: '',
    issuerUri: '',
    clientId: '',
    jwkSetUri: '',
    emailClaim: 'email',
    displayNameClaim: 'name',
    rolesClaim: '',
    systemAdminRole: '',
    auditorRole: '',
    groupsClaim: '',
  }
}

function draftFromProvider(provider: OidcProviderResponse): OidcProviderDraft {
  return {
    displayName: provider.displayName,
    issuerUri: provider.issuerUri,
    clientId: provider.clientId,
    jwkSetUri: provider.jwkSetUri ?? '',
    emailClaim: provider.claimMapping.emailClaim ?? 'email',
    displayNameClaim: provider.claimMapping.displayNameClaim ?? 'name',
    rolesClaim: provider.claimMapping.rolesClaim ?? '',
    systemAdminRole: provider.claimMapping.systemAdminRole ?? '',
    auditorRole: provider.claimMapping.auditorRole ?? '',
    groupsClaim: provider.claimMapping.groupsClaim ?? '',
  }
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

function requestFromDraft(draft: OidcProviderDraft): OidcProviderRequest {
  return {
    displayName: draft.displayName.trim(),
    issuerUri: draft.issuerUri.trim(),
    clientId: draft.clientId.trim(),
    jwkSetUri: blankToNull(draft.jwkSetUri),
    claimMapping: {
      emailClaim: draft.emailClaim.trim() || 'email',
      displayNameClaim: draft.displayNameClaim.trim() || 'name',
      rolesClaim: blankToNull(draft.rolesClaim),
      systemAdminRole: blankToNull(draft.systemAdminRole),
      auditorRole: blankToNull(draft.auditorRole),
      groupsClaim: blankToNull(draft.groupsClaim),
    },
  }
}

interface OidcProviderFormDialogProps {
  open: boolean
  /** The provider being edited; absent for creation. */
  provider?: OidcProviderResponse
  onClose: () => void
  onSaved: (provider: OidcProviderResponse) => void
}

/**
 * Creation and editing of an identity provider (ADR-0025, #1333). No secret field on purpose -
 * the SPA is a public client with PKCE. Setting a roles claim needs an explicit confirmation,
 * because it hands the system roles to the provider (ADR-0025, Entscheidung 4).
 */
export default function OidcProviderFormDialog({
  open,
  provider,
  onClose,
  onSaved,
}: OidcProviderFormDialogProps) {
  const createNewProvider = useOidcProviderStore((s) => s.createNewProvider)
  const updateExistingProvider = useOidcProviderStore((s) => s.updateExistingProvider)

  // Seeded once per mount: the page remounts the dialog (a fresh `key`) for every opening, so a
  // reopened dialog never carries the previous draft, error or test result along.
  const [draft, setDraft] = useState<OidcProviderDraft>(() =>
    provider ? draftFromProvider(provider) : emptyDraft(),
  )
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null)
  // "the confirmation is being asked" - never "it was given": only the confirmation button saves
  const [rolesConfirmationOpen, setRolesConfirmationOpen] = useState(false)

  const isValid =
    draft.displayName.trim() !== '' && draft.issuerUri.trim() !== '' && draft.clientId.trim() !== ''
  // the confirmation is owed when a roles claim is being introduced, not when it was already set
  const introducesRolesClaim =
    draft.rolesClaim.trim() !== '' && (provider?.claimMapping.rolesClaim ?? '') === ''

  function update(field: keyof OidcProviderDraft, value: string) {
    setDraft((current) => ({ ...current, [field]: value }))
  }

  async function handleTest() {
    setTestResult(null)
    setTesting(true)
    try {
      const result = await testOidcProvider({
        issuerUri: draft.issuerUri.trim(),
        jwkSetUri: blankToNull(draft.jwkSetUri),
      })
      setTestResult(result)
    } catch (err) {
      setTestResult({
        success: false,
        message: err instanceof Error ? err.message : 'Verbindungstest fehlgeschlagen',
      })
    } finally {
      setTesting(false)
    }
  }

  async function save() {
    setError(null)
    setSubmitting(true)
    try {
      const request = requestFromDraft(draft)
      const saved = provider
        ? await updateExistingProvider(provider.id, request)
        : await createNewProvider(request)
      onSaved(saved)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Anbieter konnte nicht gespeichert werden')
    } finally {
      setSubmitting(false)
    }
  }

  function handleSubmit() {
    if (!isValid) {
      setError(REQUIRED_FIELDS_HINT)
      return
    }
    if (introducesRolesClaim) {
      setRolesConfirmationOpen(true)
      return
    }
    void save()
  }

  const noRoleValues = draft.systemAdminRole.trim() === '' && draft.auditorRole.trim() === ''

  const title = provider ? `„${provider.displayName}“ bearbeiten` : 'Identitätsanbieter anlegen'

  return (
    <Dialog open={open} onClose={submitting ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        {testResult && (
          <Alert severity={testResult.success ? 'success' : 'error'} sx={{ mb: 2 }}>
            {testResult.message}
          </Alert>
        )}
        {rolesConfirmationOpen && (
          // a live region (MUI's default role="alert"): announced without a focus move, and the
          // confirming button takes the focus so the answer is one keystroke away
          <Alert
            severity="warning"
            sx={{ mb: 2 }}
            action={
              <Stack direction="row" spacing={1}>
                <Button
                  size="small"
                  onClick={() => setRolesConfirmationOpen(false)}
                  disabled={submitting}
                >
                  Abbrechen
                </Button>
                <Button
                  // eslint-disable-next-line jsx-a11y-x/no-autofocus
                  autoFocus
                  size="small"
                  variant="contained"
                  onClick={() => void save()}
                  disabled={submitting}
                >
                  Rollen-Claim setzen
                </Button>
              </Stack>
            }
          >
            {ROLES_CLAIM_CONFIRMATION}
            {noRoleValues ? ` ${ROLES_CLAIM_NO_VALUES_WARNING}` : ''}
          </Alert>
        )}
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            // eslint-disable-next-line jsx-a11y-x/no-autofocus
            autoFocus
            label="Anzeigename"
            required
            value={draft.displayName}
            onChange={(e) => update('displayName', e.target.value)}
            slotProps={{ htmlInput: { maxLength: 120 } }}
            helperText="So heißt der Anbieter auf der Anmeldeseite."
            fullWidth
            size="small"
          />
          <TextField
            label="Issuer-URI"
            required
            value={draft.issuerUri}
            onChange={(e) => update('issuerUri', e.target.value)}
            slotProps={{ htmlInput: { maxLength: 500 } }}
            helperText={ISSUER_HELP_TEXT}
            fullWidth
            size="small"
          />
          <TextField
            label="Client-ID"
            required
            value={draft.clientId}
            onChange={(e) => update('clientId', e.target.value)}
            slotProps={{ htmlInput: { maxLength: 255 } }}
            helperText="Der beim Anbieter angelegte öffentliche Client (ohne Secret, mit PKCE)."
            fullWidth
            size="small"
          />
          <TextField
            label="JWK-Set-Adresse (Backend-seitig)"
            value={draft.jwkSetUri}
            onChange={(e) => update('jwkSetUri', e.target.value)}
            slotProps={{ htmlInput: { maxLength: 500 } }}
            helperText={JWK_SET_HELP_TEXT}
            fullWidth
            size="small"
          />

          <Divider />
          <Typography variant="subtitle2" component="h3">
            Claim-Zuordnung
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Aus welchen Token-Claims OPAA E-Mail, Anzeigename und optional Rollen und Gruppen liest.
            Pfade in Punktnotation (z. B. <code>realm_access.roles</code>).
          </Typography>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="E-Mail-Claim"
              value={draft.emailClaim}
              onChange={(e) => update('emailClaim', e.target.value)}
              slotProps={{ htmlInput: { maxLength: 100 } }}
              size="small"
              sx={{ flex: 1 }}
            />
            <TextField
              label="Anzeigename-Claim"
              value={draft.displayNameClaim}
              onChange={(e) => update('displayNameClaim', e.target.value)}
              slotProps={{ htmlInput: { maxLength: 100 } }}
              helperText="Rückfall: preferred_username"
              size="small"
              sx={{ flex: 1 }}
            />
          </Stack>
          <TextField
            label="Rollen-Claim"
            value={draft.rolesClaim}
            onChange={(e) => update('rolesClaim', e.target.value)}
            slotProps={{ htmlInput: { maxLength: 200 } }}
            helperText="Leer lassen: Rollen werden in OPAA verwaltet. Gesetzt: der Anbieter ist für SYSTEM_ADMIN und AUDITOR führend."
            fullWidth
            size="small"
          />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Rollenwert für SYSTEM_ADMIN"
              value={draft.systemAdminRole}
              onChange={(e) => update('systemAdminRole', e.target.value)}
              slotProps={{ htmlInput: { maxLength: 255 } }}
              disabled={draft.rolesClaim.trim() === ''}
              size="small"
              sx={{ flex: 1 }}
            />
            <TextField
              label="Rollenwert für AUDITOR"
              value={draft.auditorRole}
              onChange={(e) => update('auditorRole', e.target.value)}
              slotProps={{ htmlInput: { maxLength: 255 } }}
              disabled={draft.rolesClaim.trim() === ''}
              size="small"
              sx={{ flex: 1 }}
            />
          </Stack>
          <TextField
            label="Gruppen-Claim"
            value={draft.groupsClaim}
            onChange={(e) => update('groupsClaim', e.target.value)}
            slotProps={{ htmlInput: { maxLength: 200 } }}
            helperText="Leer lassen: keine Gruppen aus dem Token. Gesetzt: die Gruppennamen des Tokens werden bei jeder Anmeldung zu Gruppen dieses Anbieters."
            fullWidth
            size="small"
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={submitting}>
          Abbrechen
        </Button>
        <Button
          onClick={() => void handleTest()}
          disabled={testing || draft.issuerUri.trim() === ''}
        >
          {testing ? 'Verbindung wird getestet …' : 'Verbindung testen'}
        </Button>
        {!rolesConfirmationOpen && (
          <Button variant="contained" onClick={handleSubmit} disabled={submitting || !isValid}>
            {submitting ? 'Wird gespeichert …' : provider ? 'Speichern' : 'Anlegen'}
          </Button>
        )}
      </DialogActions>
    </Dialog>
  )
}
