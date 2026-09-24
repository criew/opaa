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
import { useEffect } from 'react'
import { Navigate, Link as RouterLink, useLocation } from 'react-router'
import type { SignInProvider } from '../types/auth'
import ProviderMonogram from '../components/ProviderMonogram'
import PageHeading from '../components/a11y/PageHeading'
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
  /** Whether this tile is the page's one primary button - never while the local mask is shown. */
  emphasised: boolean
  showsLastUsed: boolean
  isSigningIn: boolean
  disabled: boolean
  onChoose: () => void
}

/**
 * One sign-in tile, one line high (#1910): monogram, the provider's name, its host as the line a
 * person recognises it by. Five of them stand under each other without scrolling, which is what
 * #1910 asks for instead of a dropdown. The action stays in the accessible name, not in the
 * visible label - the section above already says that this is how one signs in here.
 */
function ProviderChoice({
  provider,
  isSuggested,
  emphasised,
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
      variant={emphasised ? 'contained' : 'outlined'}
      fullWidth
      onClick={onChoose}
      disabled={disabled}
      // The host line is recognition, not part of the action - the accessible name stays the
      // plain verb phrase, which is also what a voice command has to be able to say.
      aria-label={label}
      aria-describedby={showsLastUsed ? lastUsedHintId : undefined}
      sx={{
        justifyContent: 'flex-start',
        textAlign: 'left',
        gap: 1.25,
        px: 1.25,
        py: 0.75,
        minHeight: 44,
        borderRadius: `${radius.md}px`,
        '&:hover .login-choice-arrow': { transform: 'translateX(3px)' },
      }}
    >
      <ProviderMonogram
        name={provider.displayName}
        tone={emphasised ? 'inverse' : 'accent'}
        size={26}
      />
      <Box
        component="span"
        sx={{ flex: 1, minWidth: 0, display: 'flex', alignItems: 'baseline', gap: 1 }}
      >
        <Box
          component="span"
          sx={{ fontSize: 14, fontWeight: 600, lineHeight: 1.3, whiteSpace: 'nowrap' }}
        >
          {isSigningIn && isSuggested ? 'Anmeldung läuft …' : provider.displayName}
        </Box>
        <Box
          component="span"
          sx={{
            flex: 1,
            minWidth: 0,
            fontSize: 11.5,
            fontWeight: 400,
            lineHeight: 1.3,
            fontFamily: fontFamily.mono,
            color: emphasised ? 'inherit' : 'text.secondary',
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
          opacity: emphasised ? 1 : 0.6,
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
  const attemptSilentSignIn = useAuthStore((s) => s.attemptSilentSignIn)
  const isSilentSignInPending = useAuthStore((s) => s.isSilentSignInPending)
  // ADR-0025: the provider used last is proposed, else the default, else the first - it gets the
  // one primary button of this surface (guidelines 5.1); the others are secondary
  const suggested = suggestedProvider()
  const lastUsedId = lastUsedProviderId()
  // The provider sign-in leaves this page, so the route travels with the flow itself.
  const returnTo = redirectTargetOf(location.state, location.search)

  // #1631: entering this page is where the automatic sign-in of a running provider session starts.
  // Only once the configuration is loaded - before that the store knows neither the providers nor
  // whether a session was restored, and the attempt would judge on an empty state. The route the
  // sign-in was started for travels with it just as it does with a clicked one (#1685). The action
  // itself decides whether this is a moment for it at all; see attemptSilentSignIn.
  useEffect(() => {
    if (isLoading) return
    void attemptSilentSignIn(returnTo)
  }, [isLoading, attemptSilentSignIn, returnTo])

  if (isAuthenticated) {
    return <Navigate to={returnTo} replace />
  }

  const hasChoice = mode === 'oidc' && providers.length > 0
  // ADR-0033, Entscheidung 4: the mask appears only while the management is switched on. Local
  // system administrators keep their own page, which is reachable at all times.
  const hasLocalForm = mode === 'oidc' && localAccounts.enabled
  // #1631: a tile the automatic redirect is about to take away is not one to click - the choice
  // becomes real once that attempt has had its turn.
  const isBusy = isLoading || isSigningIn || isSilentSignInPending()

  return (
    <AuthLayout>
      {/*
        The sign-in page is the one screen that renders before there is a session, which is why
        #582's read endpoint is reachable without authentication (#583) - otherwise the first
        thing a user sees would be the only thing that could not carry their house's mark.
      */}
      {/* Die Überschrift benennt die Seite, nicht das Produkt: Ein Screenreader sagte hier vorher
          nur den Markennamen, obwohl die Marke daneben ohnehin steht. #1910: „Anmelden" steht nur
          noch hier - die Abschnitte darunter benennen ihren Weg, nicht noch einmal die Handlung. */}
      <PageHeading title="Anmelden" variant="h5" sx={{ mb: 3 }} />

      {error && (
        <Alert severity="error" sx={{ mb: 2.5, textAlign: 'left' }}>
          <AlertTitle>Anmeldung fehlgeschlagen</AlertTitle>
          {error}
        </Alert>
      )}

      {/* #1910: Ist die Maske eingeschaltet, steht sie oben - sie ist der Weg, den diese
          Installation für ihre eigenen Konten eingerichtet hat; die Verzeichnisdienste folgen. */}
      {hasLocalForm && (
        <LocalSignInForm
          title="Konto dieser Installation"
          description="Melden Sie sich mit Ihrer E-Mail-Adresse und Ihrem Passwort an."
          showPasswordReset={localAccounts.passwordResetEnabled}
          showSelfRegistration={localAccounts.selfRegistrationEnabled}
          disabled={isLoading}
        />
      )}

      {hasChoice && hasLocalForm && <Divider sx={{ my: 3 }} />}

      {hasChoice && (
        <Box component="section" role="group" aria-labelledby="login-choice-title">
          <SectionEyebrow id="login-choice-title">Identitätsanbieter</SectionEyebrow>
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 0.5, mb: 1.5 }}>
            {providers.length > 1
              ? 'Wählen Sie den Identitätsanbieter, bei dem Sie ein Konto haben.'
              : 'Melden Sie sich mit dem Konto Ihrer Organisation an.'}
          </Typography>
          <Stack spacing={0.75}>
            {providers.map((provider) => (
              <ProviderChoice
                key={provider.id}
                provider={provider}
                isSuggested={provider.id === suggested?.id}
                // Eine Seite hat eine primäre Schaltfläche (guidelines 5.1): Steht die Maske
                // oben, gehört sie deren „Anmelden"; die Dienste bleiben dann alle sekundär.
                emphasised={!hasLocalForm && provider.id === suggested?.id}
                showsLastUsed={providers.length > 1 && provider.id === lastUsedId}
                isSigningIn={isSigningIn}
                disabled={isBusy}
                onChoose={() => void loginOidc(provider.id, { returnTo })}
              />
            ))}
          </Stack>
        </Box>
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
          <Link component={RouterLink} to={SYSTEM_LOGIN_ROUTE} sx={{ color: 'text.secondary' }}>
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
