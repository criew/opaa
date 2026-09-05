import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import LoginIcon from '@mui/icons-material/Login'
import { Navigate, useLocation } from 'react-router'
import BrandMark from '../components/BrandMark'
import { LAST_PROVIDER_STORAGE_KEY, useAuthStore } from '../stores/authStore'
import { usePageTitle } from '../hooks/usePageTitle'
import { navyRoles, radius } from '../theme/tokens'

function lastUsedProviderId(): string | null {
  try {
    return localStorage.getItem(LAST_PROVIDER_STORAGE_KEY)
  } catch {
    return null
  }
}

export default function LoginPage() {
  const location = useLocation()
  usePageTitle('Anmelden')
  const mode = useAuthStore((s) => s.mode)
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const error = useAuthStore((s) => s.error)
  const isLoading = useAuthStore((s) => s.isLoading)
  const loginOidc = useAuthStore((s) => s.loginOidc)
  const providers = useAuthStore((s) => s.providers)
  const suggestedProvider = useAuthStore((s) => s.suggestedProvider)
  // ADR-0025: the provider used last is proposed, else the default, else the first - it gets the
  // one primary button of this surface (guidelines 5.1); the others are secondary
  const suggested = suggestedProvider()
  const lastUsedId = lastUsedProviderId()

  if (isAuthenticated) {
    const from =
      typeof location.state === 'object' &&
      location.state !== null &&
      'from' in location.state &&
      typeof location.state.from === 'string'
        ? location.state.from
        : '/chat'
    return <Navigate to={from} replace />
  }

  return (
    <Box
      sx={{
        display: 'grid',
        placeItems: 'center',
        minHeight: '100vh',
        p: 3,
        // The sign-in page is a brand surface like the sidebar: navy ground in both schemes
        // (mockup 1f) - the card itself follows the active scheme.
        bgcolor: navyRoles.bg1,
      }}
    >
      <Box
        sx={{
          width: 400,
          maxWidth: '100%',
          bgcolor: 'background.paper',
          border: 2,
          borderColor: 'primary.main',
          borderRadius: `${radius.xl}px`,
          px: 4.25,
          py: 4.5,
        }}
      >
        {/*
          The sign-in page is the one screen that renders before there is a session, which is why
          #582's read endpoint is reachable without authentication (#583) - otherwise the first
          thing a user sees would be the only thing that could not carry their house's mark.
        */}
        <Box component="h1" sx={{ m: 0, mb: 3.5 }}>
          <BrandMark orientation="vertical" variant="h5" logoHeight={40} showClaim />
        </Box>

        {error && (
          <Alert severity="error" sx={{ mb: 2.5, textAlign: 'left' }}>
            <AlertTitle>Anmeldung fehlgeschlagen</AlertTitle>
            {error}
          </Alert>
        )}

        {mode === 'oidc' && providers.length > 0 && (
          <Stack spacing={1.25} component="nav" aria-label="Anmeldung">
            {providers.map((provider) => {
              const isSuggested = provider.id === suggested?.id
              const showsLastUsed = providers.length > 1 && provider.id === lastUsedId
              return (
                <Button
                  key={provider.id}
                  variant={isSuggested ? 'contained' : 'outlined'}
                  fullWidth
                  onClick={() => void loginOidc(provider.id)}
                  disabled={isLoading}
                  startIcon={<LoginIcon />}
                  endIcon={
                    showsLastUsed ? (
                      <Chip label="Zuletzt verwendet" size="small" component="span" />
                    ) : undefined
                  }
                  sx={{ py: 1.375 }}
                >
                  {isLoading && isSuggested
                    ? 'Anmeldung läuft …'
                    : `Anmelden über ${provider.displayName}`}
                </Button>
              )
            })}
            {suggested && (
              <Button
                variant="text"
                size="small"
                onClick={() => void loginOidc(suggested.id, { switchAccount: true })}
                disabled={isLoading}
                sx={{ alignSelf: 'center' }}
              >
                {providers.length > 1
                  ? `Mit anderem Konto bei ${suggested.displayName} anmelden`
                  : 'Mit anderem Konto anmelden'}
              </Button>
            )}
            {providers.length > 1 && (
              <Typography variant="caption" color="text.secondary" sx={{ textAlign: 'center' }}>
                Wählen Sie den Identitätsanbieter, bei dem Sie ein Konto haben.
              </Typography>
            )}
          </Stack>
        )}
      </Box>
    </Box>
  )
}
