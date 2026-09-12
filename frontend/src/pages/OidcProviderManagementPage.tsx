import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import AddIcon from '@mui/icons-material/Add'
import type { OidcProviderResponse } from '../types/api'
import { useAuthStore } from '../stores/authStore'
import { notify } from '../stores/notificationStore'
import { useOidcProviderStore } from '../stores/oidcProviderStore'
import { radius } from '../theme/tokens'
import PageHeading from '../components/a11y/PageHeading'
import GlobalScopeNote from '../components/GlobalScopeNote'
import SectionHead from '../components/SectionHead'
import OidcProviderCard from '../components/admin/OidcProviderCard'
import OidcProviderFormDialog from '../components/admin/OidcProviderFormDialog'
import OidcProviderSetupInstructions from '../components/admin/OidcProviderSetupInstructions'

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

  // Die `LOCAL`-Zeile ist keine Anbieterzeile dieser Seite (ADR-0033, Entscheidung 4): ihr
  // `enabled` ist der Schalter der lokalen Benutzerverwaltung und wird dort bedient
  // (Administration → Benutzer). Zwei Schalter für denselben Zustand wären zwei Wahrheiten.
  const providers = useMemo(
    () => allProviders.filter((provider) => provider.providerType === 'OIDC'),
    [allProviders],
  )
  const enabledCount = providers.filter((provider) => provider.enabled).length

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

  const openCreateDialog = () => setDialog((d) => ({ open: true, opening: d.opening + 1 }))

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: 1040 }}>
        <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2, mb: 2.5, flexWrap: 'wrap' }}>
          <PageHeading title="Identitätsanbieter" />
          <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
            {providers.length === 1 ? '1 Anbieter' : `${providers.length} Anbieter`}
          </Typography>
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            onClick={openCreateDialog}
            sx={{ ml: 'auto', flex: 'none' }}
          >
            Neuer Anbieter
          </Button>
        </Box>
        <GlobalScopeNote>
          Gilt für die gesamte Anwendung. Die Reihenfolge ist die der Anmeldeseite; Änderungen
          wirken ohne Neustart. Lokale Konten sind kein Anbieter dieser Liste – sie werden unter
          Administration → Benutzer geführt.
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
            Verzeichnisabgleich greifen nicht. Machen Sie einen aktivierten, erreichbaren Anbieter
            zum Standard oder stellen Sie den Umgebungsanbieter mit OPAA_OIDC_BOOTSTRAP=force wieder
            her.
          </Alert>
        )}

        <Box component="section" aria-labelledby="oidc-providers-title" sx={{ mb: 5 }}>
          <SectionHead id="oidc-providers-title">Anbieter in Anmeldereihenfolge</SectionHead>
          {isLoading ? (
            // Loading without a layout jump (guidelines 5.7): two card-sized skeletons.
            <Stack spacing={1.5} aria-busy="true">
              <span style={visuallyHidden}>Anbieter werden geladen …</span>
              <Skeleton variant="rounded" height={196} />
              <Skeleton variant="rounded" height={196} />
            </Stack>
          ) : providers.length === 0 ? (
            <Box
              sx={{
                border: 1,
                borderStyle: 'dashed',
                borderColor: 'divider',
                borderRadius: `${radius.md}px`,
                p: 3,
                textAlign: 'center',
              }}
            >
              <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>
                Es sind noch keine Identitätsanbieter hinterlegt.
              </Typography>
              <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
                Ohne Anbieter gibt es keine Anmeldeseite. Legen Sie zuerst den Anbieter Ihrer
                Organisation an – er wird automatisch zum Standard.
              </Typography>
              <Button
                variant="outlined"
                size="small"
                startIcon={<AddIcon />}
                onClick={openCreateDialog}
                sx={{ mt: 2 }}
              >
                Anbieter anlegen
              </Button>
            </Box>
          ) : (
            <Stack spacing={1.5}>
              {providers.map((provider, index) => (
                <OidcProviderCard
                  key={provider.id}
                  provider={provider}
                  position={index + 1}
                  isFirst={index === 0}
                  isLast={index === providers.length - 1}
                  isLastEnabled={provider.enabled && enabledCount === 1}
                  canDisable={!provider.isDefault || enabledCount <= 1}
                  canDelete={!provider.isDefault || providers.length === 1}
                  onEdit={(p) =>
                    setDialog((d) => ({ open: true, provider: p, opening: d.opening + 1 }))
                  }
                />
              ))}
            </Stack>
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
