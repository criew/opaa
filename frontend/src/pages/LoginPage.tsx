import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded'
import LockOutlinedIcon from '@mui/icons-material/LockOutlined'
import SwitchAccountOutlinedIcon from '@mui/icons-material/SwitchAccountOutlined'
import { Navigate, Link as RouterLink, useLocation } from 'react-router'
import type { SignInProvider } from '../types/auth'
import BrandMark from '../components/BrandMark'
import ProviderMonogram from '../components/ProviderMonogram'
import AuthLayout from '../components/auth/AuthLayout'
import LocalSignInForm from '../components/auth/LocalSignInForm'
import SectionEyebrow from '../components/auth/SectionEyebrow'
import { LAST_PROVIDER_STORAGE_KEY, useAuthStore } from '../stores/authStore'
import { usePageTitle } from '../hooks/usePageTitle'
import { SYSTEM_LOGIN_ROUTE } from '../routes'
import { redirectTargetOf } from '../utils/safeRedirectPath'
import { fontFamily, radius } from '../theme/tokens'

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
  const localAccounts = useAuthStore((s) => s.localAccounts)
  const suggestedProvider = useAuthStore((s) => s.suggestedProvider)
  // ADR-0025: the provider used last is proposed, else the default, else the first - it gets the
  // one primary button of this surface (guidelines 5.1); the others are secondary
  const suggested = suggestedProvider()
  const lastUsedId = lastUsedProviderId()

  if (isAuthenticated) {
    return <Navigate to={redirectTargetOf(location.state, location.search)} replace />
  }

  const hasChoice = mode === 'oidc' && providers.length > 0
  // ADR-0033, Entscheidung 4: the mask appears only while the management is switched on. Local
  // system administrators keep their own page, which is reachable at all times.
  const hasLocalForm = mode === 'oidc' && localAccounts.enabled
  const isBusy = isLoading || isSigningIn

  return (
    <AuthLayout>
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
          <SectionEyebrow id="login-choice-title">
            {hasLocalForm ? 'Mit Identitätsanbieter' : 'Anmeldung'}
          </SectionEyebrow>
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

      {hasChoice && hasLocalForm && <Divider sx={{ my: 3 }} />}

      {hasLocalForm && (
        <LocalSignInForm
          title={hasChoice ? 'Mit Konto dieser Installation' : 'Anmeldung'}
          description="Melden Sie sich mit Ihrer E-Mail-Adresse und Ihrem Passwort an."
          showPasswordReset={localAccounts.passwordResetEnabled}
          showSelfRegistration={localAccounts.selfRegistrationEnabled}
          disabled={isLoading}
        />
      )}

      <Divider sx={{ my: 3 }} />
      <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start', color: 'text.secondary' }}>
        <LockOutlinedIcon aria-hidden="true" sx={{ fontSize: 16, mt: '1px', flex: 'none' }} />
        <Typography sx={{ fontSize: 12, lineHeight: 1.5 }}>
          {trustNote(hasChoice, hasLocalForm)}
        </Typography>
      </Stack>

      {/* ADR-0033, Entscheidung 4: while the management is switched off, this quiet link is the
          only visible way to the local system administrators' sign-in. */}
      {!hasLocalForm && (
        <Typography sx={{ mt: 2, fontSize: 12, textAlign: 'center' }}>
          <Link component={RouterLink} to={SYSTEM_LOGIN_ROUTE} color="text.secondary">
            Anmeldung für die Systemverwaltung
          </Link>
        </Typography>
      )}
    </AuthLayout>
  )
}

/** The trust note, worded for what this installation actually offers (#1368). */
function trustNote(hasChoice: boolean, hasLocalForm: boolean): string {
  if (hasChoice && hasLocalForm) {
    return 'Bei Anmeldung über einen Identitätsanbieter erhält OPAA kein Kennwort. Das Passwort eines Kontos dieser Installation speichert OPAA nur als nicht umkehrbaren Prüfwert.'
  }
  if (hasChoice) {
    return 'Die Anmeldung erfolgt beim Identitätsanbieter. OPAA erhält kein Kennwort und speichert keines.'
  }
  if (hasLocalForm) {
    return 'Das Passwort eines Kontos dieser Installation speichert OPAA nur als nicht umkehrbaren Prüfwert.'
  }
  return 'Für diese Installation ist derzeit keine Anmeldung eingerichtet. Die Systemverwaltung meldet sich über die Anmeldung für die Systemverwaltung an.'
}
