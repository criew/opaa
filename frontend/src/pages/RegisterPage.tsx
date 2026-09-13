import { useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { Navigate, Link as RouterLink } from 'react-router'
import AuthLayout from '../components/auth/AuthLayout'
import AuthLoading from '../components/auth/AuthLoading'
import AuthNotice from '../components/auth/AuthNotice'
import NewPasswordField from '../components/auth/NewPasswordField'
import SectionEyebrow from '../components/auth/SectionEyebrow'
import FieldLabel from '../components/wizard/FieldLabel'
import PageHeading from '../components/a11y/PageHeading'
import { useErrorFocus } from '../hooks/useErrorFocus'
import { FieldValidationError } from '../services/authApi'
import { FlowUnavailableError, RateLimitedError, register } from '../services/selfServiceApi'
import { useAuthStore } from '../stores/authStore'
import { LOGIN_ROUTE } from '../routes'
import {
  fieldErrorMessages,
  MAIL_SENT_MESSAGE,
  passwordPolicyText,
  SELF_SERVICE_DISABLED_MESSAGE,
  SELF_SERVICE_UNREACHABLE_MESSAGE,
  tooManyRequestsMessage,
} from '../utils/authMessages'
import { radius } from '../theme/tokens'

const HEADING = 'Konto registrieren'
/** The fields this form can show an error at, in the order the focus walks them. */
const FIELDS = ['displayName', 'email', 'password'] as const

/**
 * Registers an account of this installation. The acknowledgement is the same for a free address and
 * a taken one (ADR-0033, Entscheidung 11), so the page never confirms that somebody already has an
 * account here. The assigned role and the expiry date are not shown: both are the installation's
 * decision, not the registrant's.
 */
export default function RegisterPage() {
  const isLoading = useAuthStore((s) => s.isLoading)
  const minLength = useAuthStore((s) => s.localAccounts.passwordMinLength)
  const enabled = useAuthStore((s) => s.localAccounts.selfRegistrationEnabled)

  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  const displayNameRef = useRef<HTMLInputElement>(null)
  const emailRef = useRef<HTMLInputElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)
  const alertRef = useRef<HTMLDivElement>(null)
  const focusFirstError = useErrorFocus(
    FIELDS,
    { displayName: displayNameRef, email: emailRef, password: passwordRef },
    alertRef,
  )

  function failWith(errors: Record<string, string>, message: string | null) {
    setFieldErrors(errors)
    setFormError(message)
    focusFirstError(errors)
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setFieldErrors({})
    setFormError(null)
    try {
      await register(email.trim(), displayName.trim(), password)
      setDone(true)
    } catch (err) {
      if (err instanceof FieldValidationError && err.fieldErrors.length > 0) {
        const { byField, unassigned } = fieldErrorMessages(err.fieldErrors, minLength, FIELDS)
        failWith(byField, unassigned.length > 0 ? unassigned.join(' ') : null)
      } else if (err instanceof FieldValidationError) {
        failWith({}, err.message)
      } else if (err instanceof RateLimitedError) {
        failWith({}, tooManyRequestsMessage(err.retryAfterSeconds))
      } else if (err instanceof FlowUnavailableError) {
        failWith({}, SELF_SERVICE_DISABLED_MESSAGE)
      } else {
        failWith({}, SELF_SERVICE_UNREACHABLE_MESSAGE)
      }
    } finally {
      setBusy(false)
    }
  }

  // See ForgotPasswordPage: nothing is decided while the configuration is still loading.
  if (isLoading) {
    return (
      <AuthLayout>
        <AuthLoading heading={HEADING} />
      </AuthLayout>
    )
  }
  if (!enabled) return <Navigate to={LOGIN_ROUTE} replace />

  if (done) {
    return (
      <AuthLayout>
        <AuthNotice
          heading={HEADING}
          severity="success"
          message={MAIL_SENT_MESSAGE}
          detail="Bestätigen Sie darin Ihre E-Mail-Adresse; der Link gilt 24 Stunden. Erst danach können Sie sich anmelden."
        >
          <Stack spacing={1.5} sx={{ alignItems: 'flex-start' }}>
            <Link component={RouterLink} to={LOGIN_ROUTE}>
              Zur Anmeldung
            </Link>
            {/* A second registration of an address that is still unconfirmed sends the
                verification link again and keeps the stored hash and name (#1538) - so "no mail
                arrived" has a way out that needs no administrator. The entry stays what it was;
                the sentence says so, because the form behind this still shows a password field
                whose content is no longer what decides. */}
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
              Keine E-Mail erhalten?{' '}
              <Link component="button" type="button" onClick={() => setDone(false)}>
                Registrierung erneut absenden
              </Link>{' '}
              — Ihr Passwort von vorhin bleibt gültig.
            </Typography>
          </Stack>
        </AuthNotice>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout>
      <PageHeading title={HEADING} variant="h5" />
      {formError && (
        <Alert ref={alertRef} tabIndex={-1} severity="error" sx={{ mt: 2, textAlign: 'left' }}>
          {formError}
        </Alert>
      )}
      <Box
        component="form"
        aria-labelledby="register-title"
        onSubmit={submit}
        noValidate
        sx={{ mt: 3 }}
      >
        <SectionEyebrow id="register-title">Neues Konto</SectionEyebrow>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 0.5, mb: 2 }}>
          Ihr Haus lässt die Registrierung für bestimmte E-Mail-Domänen zu. Nach der Registrierung
          bestätigen Sie Ihre Adresse über einen Link, den wir Ihnen schicken.
        </Typography>
        <Stack spacing={2}>
          <Box>
            <FieldLabel htmlFor="register-display-name">Name</FieldLabel>
            <TextField
              id="register-display-name"
              inputRef={displayNameRef}
              size="small"
              fullWidth
              autoComplete="name"
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
              disabled={busy}
              error={Boolean(fieldErrors.displayName)}
              helperText={fieldErrors.displayName}
            />
          </Box>
          <Box>
            <FieldLabel htmlFor="register-email">E-Mail-Adresse</FieldLabel>
            <TextField
              id="register-email"
              inputRef={emailRef}
              type="email"
              size="small"
              fullWidth
              autoComplete="username"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              disabled={busy}
              error={Boolean(fieldErrors.email)}
              helperText={fieldErrors.email}
            />
          </Box>
          <Box>
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 1 }}>
              {passwordPolicyText(minLength)}
            </Typography>
            <NewPasswordField
              id="register-password"
              label="Passwort"
              value={password}
              onChange={setPassword}
              minLength={minLength}
              disabled={busy}
              errorMessage={fieldErrors.password}
              inputRef={passwordRef}
            />
          </Box>
          <Button
            type="submit"
            variant="contained"
            disabled={busy}
            sx={{ alignSelf: 'flex-start', borderRadius: `${radius.md}px`, minWidth: 140 }}
          >
            {busy ? 'Wird registriert …' : 'Konto registrieren'}
          </Button>
        </Stack>
      </Box>
      {/* The installation's own privacy notice is not a page of this product yet (#143) - naming
          where it is would be a promise the application cannot keep. */}
      <Typography sx={{ mt: 3, fontSize: 12, color: 'text.secondary' }}>
        Mit der Registrierung werden Ihr Name und Ihre E-Mail-Adresse in dieser Installation
        gespeichert. Den Datenschutzhinweis Ihres Hauses erhalten Sie von Ihrer Systemverwaltung.
      </Typography>
      <Typography sx={{ mt: 2, fontSize: 12, textAlign: 'center' }}>
        <Link component={RouterLink} to={LOGIN_ROUTE} color="text.secondary">
          Sie haben schon ein Konto? Zur Anmeldung
        </Link>
      </Typography>
    </AuthLayout>
  )
}
