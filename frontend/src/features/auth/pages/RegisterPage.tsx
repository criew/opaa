import { useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { Navigate, Link as RouterLink } from 'react-router'
import AuthLayout from '../components/AuthLayout'
import AuthLoading from '../components/AuthLoading'
import AuthNotice from '../components/AuthNotice'
import SectionEyebrow from '../components/SectionEyebrow'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import { useErrorFocus } from '@/shared/hooks/useErrorFocus'
import { FieldValidationError } from '@/shared/session/authApi'
import { FlowUnavailableError, RateLimitedError, register } from '../api/selfServiceApi'
import { useAuthStore } from '@/shared/session/authStore'
import { LOGIN_ROUTE } from '@/shared/routes'
import {
  fieldErrorMessages,
  MAIL_SENT_MESSAGE,
  SELF_SERVICE_DISABLED_MESSAGE,
  SELF_SERVICE_UNREACHABLE_MESSAGE,
  tooManyRequestsMessage,
} from '@/shared/session/authMessages'
import { radius } from '@/shared/theme/tokens'

const HEADING = 'Konto registrieren'
/** The fields this form can show an error at, in the order the focus walks them. */
const FIELDS = ['email'] as const

/**
 * Registers an account of this installation. The acknowledgement is the same for a free address and
 * a taken one (ADR-0033, Entscheidung 11), so the page never confirms that somebody already has an
 * account here. The assigned role and the expiry date are not shown: both are the installation's
 * decision, not the registrant's. The form asks for the address alone - the name is set by whoever
 * redeems the verification link, so nobody can put a name on somebody else's account.
 */
export default function RegisterPage() {
  const isLoading = useAuthStore((s) => s.isLoading)
  const minLength = useAuthStore((s) => s.localAccounts.passwordMinLength)
  const enabled = useAuthStore((s) => s.localAccounts.selfRegistrationEnabled)

  const [email, setEmail] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  const emailRef = useRef<HTMLInputElement>(null)
  const alertRef = useRef<HTMLDivElement>(null)
  const focusFirstError = useErrorFocus(FIELDS, { email: emailRef }, alertRef)

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
      await register(email.trim())
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
          detail="Über den Link darin bestätigen Sie Ihre E-Mail-Adresse und legen Ihren Namen und Ihr Passwort fest; der Link gilt 24 Stunden. Erst danach können Sie sich anmelden."
        >
          <Stack spacing={1.5} sx={{ alignItems: 'flex-start' }}>
            <Link component={RouterLink} to={LOGIN_ROUTE}>
              Zur Anmeldung
            </Link>
            {/* A second registration of an address that is still unconfirmed sends the
                verification link again - so "no mail arrived" has a way out that needs no
                administrator. */}
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
              Keine E-Mail erhalten?{' '}
              <Link component="button" type="button" onClick={() => setDone(false)}>
                Registrierung erneut absenden
              </Link>
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
          schicken wir Ihnen einen Link; darüber bestätigen Sie Ihre Adresse und legen Ihren Namen
          und Ihr Passwort fest.
        </Typography>
        <Stack spacing={2}>
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
      {/* The installation's own privacy notice is not a page of this product yet - naming
          where it is would be a promise the application cannot keep. */}
      <Typography sx={{ mt: 3, fontSize: 12, color: 'text.secondary' }}>
        Mit der Registrierung wird Ihre E-Mail-Adresse in dieser Installation gespeichert, mit der
        Bestätigung auch Ihr Name. Den Datenschutzhinweis Ihres Hauses erhalten Sie von Ihrer
        Systemverwaltung.
      </Typography>
      <Typography sx={{ mt: 2, fontSize: 12, textAlign: 'center' }}>
        <Link component={RouterLink} to={LOGIN_ROUTE} sx={{ color: 'text.secondary' }}>
          Sie haben schon ein Konto? Zur Anmeldung
        </Link>
      </Typography>
    </AuthLayout>
  )
}
