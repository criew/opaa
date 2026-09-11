import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Divider from '@mui/material/Divider'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import LockOutlinedIcon from '@mui/icons-material/LockOutlined'
import { Navigate, Link as RouterLink, useLocation } from 'react-router'
import BrandMark from '../components/BrandMark'
import AuthLayout from '../components/auth/AuthLayout'
import LocalSignInForm from '../components/auth/LocalSignInForm'
import { useAuthStore } from '../stores/authStore'
import { usePageTitle } from '../hooks/usePageTitle'
import { LOGIN_ROUTE } from '../routes'
import { redirectTargetOf } from '../utils/safeRedirectPath'

/**
 * The sign-in of the local system administrators (#1368, ADR-0033, Entscheidung 4 and 5). It is
 * reachable at all times - it is the way back into an installation whose last identity provider is
 * misconfigured. Once the local account management is switched on there is nothing this page could
 * do that the regular sign-in does not, so it sends the visitor there.
 */
export default function SystemLoginPage() {
  const location = useLocation()
  usePageTitle('Anmeldung für die Systemverwaltung')
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const isLoading = useAuthStore((s) => s.isLoading)
  const localAccounts = useAuthStore((s) => s.localAccounts)
  const error = useAuthStore((s) => s.error)

  if (isAuthenticated) {
    return <Navigate to={redirectTargetOf(location.state, location.search)} replace />
  }
  if (localAccounts.enabled) {
    return <Navigate to={LOGIN_ROUTE} replace />
  }

  return (
    <AuthLayout>
      <Box component="h1" sx={{ m: 0, mb: 3.5 }}>
        <BrandMark orientation="vertical" variant="h5" logoHeight={40} showClaim />
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2.5, textAlign: 'left' }}>
          <AlertTitle>Anmeldung fehlgeschlagen</AlertTitle>
          {error}
        </Alert>
      )}

      <LocalSignInForm
        title="Anmeldung für die Systemverwaltung"
        description="Nur für lokale Systemverwalter-Konten. Alle anderen Konten melden sich über die reguläre Anmeldung an."
        disabled={isLoading}
      />

      <Divider sx={{ my: 3 }} />
      <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start', color: 'text.secondary' }}>
        <LockOutlinedIcon aria-hidden="true" sx={{ fontSize: 16, mt: '1px', flex: 'none' }} />
        <Typography sx={{ fontSize: 12, lineHeight: 1.5 }}>
          Das Passwort eines Kontos dieser Installation speichert OPAA nur als nicht umkehrbaren
          Prüfwert.
        </Typography>
      </Stack>

      <Typography sx={{ mt: 2, fontSize: 12, textAlign: 'center' }}>
        <Link component={RouterLink} to={LOGIN_ROUTE} color="text.secondary">
          Zur regulären Anmeldung
        </Link>
      </Typography>
    </AuthLayout>
  )
}
