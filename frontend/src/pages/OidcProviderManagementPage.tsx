import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward'
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward'
import type { OidcProviderResponse } from '../types/api'
import { useAuthStore } from '../stores/authStore'
import { notify } from '../stores/notificationStore'
import { useOidcProviderStore } from '../stores/oidcProviderStore'
import PageHeading from '../components/a11y/PageHeading'
import GlobalScopeNote from '../components/GlobalScopeNote'
import OidcProviderFormDialog from '../components/admin/OidcProviderFormDialog'
import OidcProviderSetupInstructions from '../components/admin/OidcProviderSetupInstructions'

const DISABLE_CONSEQUENCE =
  'Nutzer dieses Anbieters können sich ab sofort nicht mehr anmelden; laufende Sitzungen enden ' +
  'mit der nächsten Anfrage. Die Konten und ihre Rechte bleiben erhalten.'
const DELETE_CONSEQUENCE =
  'Nutzer dieses Anbieters können sich nicht mehr anmelden. Die Konten bleiben erhalten und ' +
  'werden wieder nutzbar, sobald ein Anbieter mit derselben Issuer-URI existiert.'
const DEFAULT_CONSEQUENCE =
  'Der Standardanbieter ist der einzige, der weder deaktiviert noch gelöscht werden kann; die ' +
  'Erstadministrator-Regel und der Verzeichnisabgleich gelten nur für seine Konten.'

function registryChip(provider: OidcProviderResponse) {
  if (!provider.enabled) {
    return <Chip label="Deaktiviert" size="small" />
  }
  if (provider.registryState === 'READY') {
    return <Chip label="Erreichbar" color="success" size="small" variant="outlined" />
  }
  return (
    <Tooltip title={provider.registryMessage ?? 'Schlüssel nicht abrufbar'}>
      <Chip
        label="Nicht erreichbar"
        color="error"
        size="small"
        aria-label={`Nicht erreichbar: ${provider.registryMessage ?? 'Schlüssel nicht abrufbar'}`}
      />
    </Tooltip>
  )
}

interface ProviderCardProps {
  provider: OidcProviderResponse
  isFirst: boolean
  isLast: boolean
  onEdit: (provider: OidcProviderResponse) => void
}

function ProviderCard({ provider, isFirst, isLast, onEdit }: ProviderCardProps) {
  const setProviderEnabled = useOidcProviderStore((s) => s.setProviderEnabled)
  const makeProviderDefault = useOidcProviderStore((s) => s.makeProviderDefault)
  const deleteExistingProvider = useOidcProviderStore((s) => s.deleteExistingProvider)
  const moveProvider = useOidcProviderStore((s) => s.moveProvider)
  const [localError, setLocalError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function run(action: () => Promise<unknown>, fallback: string) {
    setLocalError(null)
    setBusy(true)
    try {
      await action()
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : fallback)
    } finally {
      setBusy(false)
    }
  }

  function toggleEnabled() {
    if (
      provider.enabled &&
      !window.confirm(`„${provider.displayName}“ deaktivieren?\n\n${DISABLE_CONSEQUENCE}`)
    ) {
      return
    }
    void run(async () => {
      await setProviderEnabled(provider.id, !provider.enabled)
      notify(
        provider.enabled
          ? `„${provider.displayName}“ wurde deaktiviert.`
          : `„${provider.displayName}“ wurde aktiviert.`,
        'success',
      )
    }, 'Änderung fehlgeschlagen')
  }

  function makeDefault() {
    if (
      !window.confirm(
        `„${provider.displayName}“ zum Standardanbieter machen?\n\n${DEFAULT_CONSEQUENCE}`,
      )
    ) {
      return
    }
    void run(async () => {
      await makeProviderDefault(provider.id)
      notify(`„${provider.displayName}“ ist jetzt der Standardanbieter.`, 'success')
    }, 'Änderung fehlgeschlagen')
  }

  function remove() {
    if (!window.confirm(`„${provider.displayName}“ löschen?\n\n${DELETE_CONSEQUENCE}`)) {
      return
    }
    void run(async () => {
      await deleteExistingProvider(provider.id)
      notify(`„${provider.displayName}“ wurde gelöscht.`, 'success')
    }, 'Löschen fehlgeschlagen')
  }

  const rolesManaged = provider.claimMapping.rolesClaim !== null

  return (
    <Paper
      variant="outlined"
      component="article"
      aria-labelledby={`oidc-provider-${provider.id}-title`}
      data-testid={`oidc-provider-card-${provider.id}`}
      sx={{ p: 2.5 }}
    >
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <Typography
          id={`oidc-provider-${provider.id}-title`}
          component="h2"
          sx={{ fontSize: 14.5, fontWeight: 600, m: 0 }}
        >
          {provider.displayName}
        </Typography>
        {provider.isDefault && (
          <Chip label="Standard" color="primary" size="small" aria-label="Standardanbieter" />
        )}
        {registryChip(provider)}
        {rolesManaged && <Chip label="Rollen aus dem Token" size="small" variant="outlined" />}
        <Stack direction="row" sx={{ ml: 'auto' }}>
          <Tooltip title="Nach oben">
            <span>
              <IconButton
                size="small"
                aria-label={`„${provider.displayName}“ nach oben verschieben`}
                disabled={isFirst || busy}
                onClick={() =>
                  void run(() => moveProvider(provider.id, 'up'), 'Verschieben fehlgeschlagen')
                }
              >
                <ArrowUpwardIcon fontSize="small" />
              </IconButton>
            </span>
          </Tooltip>
          <Tooltip title="Nach unten">
            <span>
              <IconButton
                size="small"
                aria-label={`„${provider.displayName}“ nach unten verschieben`}
                disabled={isLast || busy}
                onClick={() =>
                  void run(() => moveProvider(provider.id, 'down'), 'Verschieben fehlgeschlagen')
                }
              >
                <ArrowDownwardIcon fontSize="small" />
              </IconButton>
            </span>
          </Tooltip>
        </Stack>
      </Stack>
      <Typography sx={{ fontSize: 13, color: 'text.secondary', mt: 0.5 }}>
        {provider.issuerUri} · Client „{provider.clientId}“
      </Typography>
      {provider.enabled && provider.registryState !== 'READY' && provider.registryMessage && (
        <Alert severity="warning" sx={{ mt: 1.5 }}>
          {provider.registryMessage}
        </Alert>
      )}
      {localError && (
        <Alert severity="error" sx={{ mt: 1.5 }} onClose={() => setLocalError(null)}>
          {localError}
        </Alert>
      )}
      <Stack direction="row" spacing={1} sx={{ mt: 1.5, flexWrap: 'wrap' }}>
        <Button size="small" variant="outlined" onClick={() => onEdit(provider)} disabled={busy}>
          Bearbeiten
        </Button>
        {!provider.isDefault && (
          <Button size="small" onClick={toggleEnabled} disabled={busy}>
            {provider.enabled ? 'Deaktivieren' : 'Aktivieren'}
          </Button>
        )}
        {!provider.isDefault && provider.enabled && (
          <Button size="small" onClick={makeDefault} disabled={busy}>
            Zum Standard machen
          </Button>
        )}
        {!provider.isDefault && (
          <Button size="small" color="error" onClick={remove} disabled={busy}>
            Löschen
          </Button>
        )}
      </Stack>
      {provider.isDefault && (
        <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 1 }}>
          Der Standardanbieter kann weder deaktiviert noch gelöscht werden – zuerst einen anderen
          Anbieter zum Standard machen.
        </Typography>
      )}
    </Paper>
  )
}

export default function OidcProviderManagementPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const mode = useAuthStore((s) => s.mode)
  const providers = useOidcProviderStore((s) => s.providers)
  const isLoading = useOidcProviderStore((s) => s.isLoading)
  const error = useOidcProviderStore((s) => s.error)
  const loadProviders = useOidcProviderStore((s) => s.loadProviders)
  // `opening` is the dialog's key: every opening mounts a fresh dialog with a fresh draft
  const [dialog, setDialog] = useState<{
    open: boolean
    provider?: OidcProviderResponse
    opening: number
  }>({ open: false, opening: 0 })

  useEffect(() => {
    if (isSystemAdmin) void loadProviders()
  }, [isSystemAdmin, loadProviders])

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: 4, maxWidth: 720 }}>
        <PageHeading title="Identitätsanbieter" gutterBottom />
        <Alert severity="info">
          Die Anbieterverwaltung wird von der Systemverwaltung gepflegt. Für Ihr Konto ist diese
          Seite nicht freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2, mb: 2.5, flexWrap: 'wrap' }}>
        <PageHeading title="Identitätsanbieter" />
        <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
          {providers.length === 1 ? '1 Anbieter' : `${providers.length} Anbieter`}
        </Typography>
        <Button
          variant="contained"
          onClick={() => setDialog((d) => ({ open: true, opening: d.opening + 1 }))}
          sx={{ ml: 'auto', flex: 'none' }}
        >
          Neuer Anbieter
        </Button>
      </Box>
      <GlobalScopeNote>
        Gilt für die gesamte Anwendung. Die Reihenfolge ist die der Anmeldeseite; Änderungen wirken
        ohne Neustart.
      </GlobalScopeNote>

      {mode === 'dev' && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Im Entwicklungsmodus meldet das Backend jede Anfrage als Entwicklungsnutzer an; die hier
          hinterlegten Anbieter wirken erst im OIDC-Modus.
        </Alert>
      )}

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {!isLoading && providers.length > 0 && !providers.some((p) => p.isDefault) && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          Kein Anbieter ist Standardanbieter – die Erstadministrator-Regel und der
          Verzeichnisabgleich greifen nicht. Machen Sie einen aktivierten, erreichbaren Anbieter zum
          Standard oder stellen Sie den Umgebungsanbieter mit OPAA_OIDC_BOOTSTRAP=force wieder her.
        </Alert>
      )}

      {isLoading ? (
        <Typography color="text.secondary">Anbieter werden geladen …</Typography>
      ) : providers.length === 0 ? (
        <Typography color="text.secondary">
          Es sind noch keine Identitätsanbieter hinterlegt.
        </Typography>
      ) : (
        <Stack spacing={1.5}>
          {providers.map((provider, index) => (
            <ProviderCard
              key={provider.id}
              provider={provider}
              isFirst={index === 0}
              isLast={index === providers.length - 1}
              onEdit={(p) =>
                setDialog((d) => ({ open: true, provider: p, opening: d.opening + 1 }))
              }
            />
          ))}
        </Stack>
      )}

      <Divider sx={{ my: 4 }} />
      <OidcProviderSetupInstructions />

      <OidcProviderFormDialog
        key={dialog.opening}
        open={dialog.open}
        provider={dialog.provider}
        onClose={() => setDialog((d) => ({ ...d, open: false }))}
        onSaved={(saved) => {
          setDialog((d) => ({ ...d, open: false }))
          notify(`„${saved.displayName}“ wurde gespeichert.`, 'success')
        }}
      />
    </Box>
  )
}
