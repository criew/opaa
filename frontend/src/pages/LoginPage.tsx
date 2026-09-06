import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { keyframes } from '@mui/material/styles'
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded'
import LockOutlinedIcon from '@mui/icons-material/LockOutlined'
import SwitchAccountOutlinedIcon from '@mui/icons-material/SwitchAccountOutlined'
import { Navigate, useLocation } from 'react-router'
import type { SignInProvider } from '../types/auth'
import BrandMark from '../components/BrandMark'
import ProviderMonogram from '../components/ProviderMonogram'
import { LAST_PROVIDER_STORAGE_KEY, useAuthStore } from '../stores/authStore'
import { usePageTitle } from '../hooks/usePageTitle'
import { fontFamily, navyRoles, radius } from '../theme/tokens'

// One staged entrance for the card (guidelines 4.5); the theme collapses it under
// prefers-reduced-motion.
const cardReveal = keyframes`
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: none; }
`

function lastUsedProviderId(): string | null {
  try {
    return localStorage.getItem(LAST_PROVIDER_STORAGE_KEY)
  } catch {
    return null
  }
}

/** The host a person recognises their provider by; the raw value if the URI does not parse. */
function providerHost(issuerUri: string): string {
  try {
    return new URL(issuerUri).host
  } catch {
    return issuerUri
  }
}

interface ProviderChoiceProps {
  provider: SignInProvider
  isSuggested: boolean
  showsLastUsed: boolean
  isSigningIn: boolean
  disabled: boolean
  onChoose: () => void
}

/**
 * One sign-in tile: monogram, the action as its label, the provider's host as the line a person
 * recognises it by. The suggested provider is the page's one primary button (guidelines 5.1).
 */
function ProviderChoice({
  provider,
  isSuggested,
  showsLastUsed,
  isSigningIn,
  disabled,
  onChoose,
}: ProviderChoiceProps) {
  const label =
    isSigningIn && isSuggested ? 'Anmeldung läuft …' : `Anmelden bei ${provider.displayName}`
  const lastUsedHintId = `login-${provider.id}-last-used`
  return (
    <Button
      variant={isSuggested ? 'contained' : 'outlined'}
      fullWidth
      onClick={onChoose}
      disabled={disabled}
      // The host line is recognition, not part of the action - the name stays the plain verb
      // phrase the tile shows in its first line.
      aria-label={label}
      aria-describedby={showsLastUsed ? lastUsedHintId : undefined}
      sx={{
        justifyContent: 'flex-start',
        textAlign: 'left',
        gap: 1.5,
        px: 1.5,
        py: 1.25,
        minHeight: 64,
        borderRadius: `${radius.md}px`,
        '&:hover .login-choice-arrow': { transform: 'translateX(3px)' },
      }}
    >
      <ProviderMonogram name={provider.displayName} tone={isSuggested ? 'inverse' : 'accent'} />
      <Box component="span" sx={{ flex: 1, minWidth: 0 }}>
        <Box
          component="span"
          sx={{ display: 'block', fontSize: 14.5, fontWeight: 600, lineHeight: 1.25 }}
        >
          {label}
        </Box>
        <Box
          component="span"
          sx={{
            display: 'block',
            mt: 0.5,
            fontSize: 12,
            fontWeight: 400,
            lineHeight: 1.25,
            fontFamily: fontFamily.mono,
            color: isSuggested ? 'inherit' : 'text.secondary',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {providerHost(provider.issuerUri)}
          {showsLastUsed && (
            <>
              {' · '}
              <Box component="span" id={lastUsedHintId} sx={{ fontFamily: fontFamily.sans }}>
                Zuletzt verwendet
              </Box>
            </>
          )}
        </Box>
      </Box>
      <ArrowForwardRoundedIcon
        className="login-choice-arrow"
        fontSize="small"
        sx={{
          flex: 'none',
          opacity: isSuggested ? 1 : 0.6,
          transition: 'transform 150ms ease-out',
        }}
      />
    </Button>
  )
}

export default function LoginPage() {
  const location = useLocation()
  usePageTitle('Anmelden')
  const mode = useAuthStore((s) => s.mode)
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const error = useAuthStore((s) => s.error)
  const isLoading = useAuthStore((s) => s.isLoading)
  const isSigningIn = useAuthStore((s) => s.isSigningIn)
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

  const hasChoice = mode === 'oidc' && providers.length > 0
  const isBusy = isLoading || isSigningIn

  return (
    <Box
      sx={{
        display: 'grid',
        // minmax(0, 1fr): the track may shrink below the card's preferred width, so a narrow
        // viewport narrows the card instead of scrolling sideways
        gridTemplateColumns: 'minmax(0, 1fr)',
        placeItems: 'center',
        minHeight: '100vh',
        p: { xs: 2, sm: 3 },
        // The sign-in page is a brand surface like the sidebar: navy ground in both schemes
        // (mockup 1f) - the card itself follows the active scheme.
        bgcolor: navyRoles.bg1,
      }}
    >
      <Box
        sx={{
          width: '100%',
          maxWidth: 440,
          bgcolor: 'background.paper',
          border: 2,
          borderColor: 'primary.main',
          borderRadius: `${radius.xl}px`,
          px: { xs: 3, sm: 4.25 },
          py: 4.5,
          animation: `${cardReveal} 240ms ease-out both`,
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

        {hasChoice && (
          <Box component="section" role="group" aria-labelledby="login-choice-title">
            <Typography
              id="login-choice-title"
              component="h2"
              sx={{
                m: 0,
                fontFamily: fontFamily.mono,
                fontSize: 10,
                fontWeight: 500,
                letterSpacing: '0.08em',
                textTransform: 'uppercase',
                color: 'primary.main',
              }}
            >
              Anmeldung
            </Typography>
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 0.5, mb: 2 }}>
              {providers.length > 1
                ? 'Wählen Sie den Identitätsanbieter, bei dem Sie ein Konto haben.'
                : 'Melden Sie sich mit dem Konto Ihrer Organisation an.'}
            </Typography>
            <Stack spacing={1}>
              {providers.map((provider) => (
                <ProviderChoice
                  key={provider.id}
                  provider={provider}
                  isSuggested={provider.id === suggested?.id}
                  showsLastUsed={providers.length > 1 && provider.id === lastUsedId}
                  isSigningIn={isSigningIn}
                  disabled={isBusy}
                  onChoose={() => void loginOidc(provider.id)}
                />
              ))}
            </Stack>
            {suggested && (
              <Button
                variant="text"
                size="small"
                startIcon={<SwitchAccountOutlinedIcon />}
                onClick={() => void loginOidc(suggested.id, { switchAccount: true })}
                disabled={isBusy}
                sx={{ display: 'flex', mx: 'auto', mt: 1.5 }}
              >
                {providers.length > 1
                  ? `Mit anderem Konto bei ${suggested.displayName} anmelden`
                  : 'Mit anderem Konto anmelden'}
              </Button>
            )}
          </Box>
        )}

        <Divider sx={{ my: 3 }} />
        <Stack
          direction="row"
          spacing={1}
          sx={{ alignItems: 'flex-start', color: 'text.secondary' }}
        >
          <LockOutlinedIcon aria-hidden="true" sx={{ fontSize: 16, mt: '1px', flex: 'none' }} />
          <Typography sx={{ fontSize: 12, lineHeight: 1.5 }}>
            Die Anmeldung erfolgt beim Identitätsanbieter. OPAA erhält kein Kennwort und speichert
            keines.
          </Typography>
        </Stack>
      </Box>
    </Box>
  )
}
