import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import AddIcon from '@mui/icons-material/Add'
import FingerprintOutlinedIcon from '@mui/icons-material/FingerprintOutlined'
import type { OidcProviderResponse } from '@/shared/api/types'
import { useAuthStore } from '@/shared/session/authStore'
import { notify } from '@/shared/stores/notificationStore'
import { useOidcProviderStore } from '../stores/oidcProviderStore'
import { contentWidth } from '@/shared/theme/tokens'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import SectionHead from '@/shared/ui/SectionHead'
import ProviderList from '../components/ProviderList'
import OidcProviderFormDialog from '../components/OidcProviderFormDialog'
import { useProviderReturn } from '@/shared/session/useRecentLoginGate'
import OidcProviderSetupInstructions from '../components/OidcProviderSetupInstructions'
import EmptyState from '@/shared/ui/states/EmptyState'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

export default function OidcProviderManagementPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const mode = useAuthStore((s) => s.mode)
  const allProviders = useOidcProviderStore((s) => s.providers)
  const isLoading = useOidcProviderStore((s) => s.isLoading)
  const error = useOidcProviderStore((s) => s.error)
  const loadProviders = useOidcProviderStore((s) => s.loadProviders)
  // `opening` is the dialog's key: every opening mounts a fresh dialog with a fresh draft
  const [dialog, setDialog] = useState<{
    open: boolean
    provider?: OidcProviderResponse
    opening: number
  }>({ open: false, opening: 0 })

  // The `LOCAL` row is no provider row of this page (ADR-0033, Entscheidung 4): its
  // `enabled` is the switch of the local user management and is operated there
  // (Administration → Benutzer). Two switches for the same state would be two truths.
  const providers = useMemo(
    () => allProviders.filter((provider) => provider.providerType === 'OIDC'),
    [allProviders],
  )

  useEffect(() => {
    if (isSystemAdmin) void loadProviders()
  }, [isSystemAdmin, loadProviders])

  // Back from a sign-in forced at the provider before a role mapping was saved: the dialog is gone
  // with the page, so the person saves again; the gate holds the return until that attempt.
  useProviderReturn(
    'Sie sind von Ihrem Anmeldedienst zurück. Bitte speichern Sie den Anbieter jetzt erneut.',
  )

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Identitätsanbieter" gutterBottom />
        <Alert severity="info">
          Die Anbieterverwaltung wird von der Systemverwaltung gepflegt. Für Ihr Konto ist diese
          Seite nicht freigegeben.
        </Alert>
      </Box>
    )
  }

  const openCreateDialog = () => setDialog((d) => ({ open: true, opening: d.opening + 1 }))

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={FingerprintOutlinedIcon}
          title="Identitätsanbieter"
          meta={providers.length === 1 ? '1 Anbieter' : `${providers.length} Anbieter`}
          description="Anbieter in der Reihenfolge der Anmeldeseite; Änderungen wirken ohne Neustart. Lokale Konten stehen unter Administration → Benutzer."
          action={
            <Button variant="contained" startIcon={<AddIcon />} onClick={openCreateDialog}>
              Neuer Anbieter
            </Button>
          }
        />

        {mode === 'dev' && (
          <Alert severity="info" sx={{ mb: 2 }}>
            Im Entwicklungsmodus meldet das Backend jede Anfrage als Entwicklungsnutzer an; die hier
            hinterlegten Anbieter wirken erst im OIDC-Modus.
          </Alert>
        )}

        {error && providers.length > 0 && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {!isLoading && providers.length > 0 && !providers.some((p) => p.isDefault) && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            Kein Anbieter ist Standardanbieter: Anmeldevorschlag und Löschschutz greifen nicht.
            Machen Sie einen aktivierten, erreichbaren Anbieter zum Standard. Kann sich niemand mehr
            anmelden, hilft OPAA_LOCAL_ADMIN_RESET=force (Notfallprozedur im Handbuch). Der
            Verzeichnisabgleich hängt nicht daran.
          </Alert>
        )}

        <Box component="section" aria-labelledby="oidc-providers-title" sx={{ mb: 5 }}>
          <SectionHead id="oidc-providers-title">Anbieter in Anmeldereihenfolge</SectionHead>
          {isLoading ? (
            <LoadingState shape="cards" count={2} />
          ) : error && providers.length === 0 ? (
            <LoadError
              message="Die Identitätsanbieter konnten nicht geladen werden."
              detail={error}
              onRetry={() => void loadProviders()}
            />
          ) : providers.length === 0 ? (
            <EmptyState
              framed
              message="Noch keine Identitätsanbieter. Ohne Anbieter gibt es keine Anmeldeseite; der erste wird automatisch zum Standard."
              action={
                <Button variant="outlined" startIcon={<AddIcon />} onClick={openCreateDialog}>
                  Neuer Anbieter
                </Button>
              }
            />
          ) : (
            <ProviderList
              providers={providers}
              onEdit={(p) =>
                setDialog((d) => ({ open: true, provider: p, opening: d.opening + 1 }))
              }
            />
          )}
        </Box>

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
    </Box>
  )
}
