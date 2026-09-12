import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { Navigate, Link as RouterLink } from 'react-router'
import AuthLayout from '../components/auth/AuthLayout'
import AuthNotice from '../components/auth/AuthNotice'
import SectionEyebrow from '../components/auth/SectionEyebrow'
import FieldLabel from '../components/wizard/FieldLabel'
import PageHeading from '../components/a11y/PageHeading'
import { FieldValidationError } from '../services/authApi'
import { FlowUnavailableError, forgotPassword, RateLimitedError } from '../services/selfServiceApi'
import { useAuthStore } from '../stores/authStore'
import { LOGIN_ROUTE } from '../routes'
import {
  fieldErrorMessages,
  MAIL_SENT_MESSAGE,
  SELF_SERVICE_DISABLED_MESSAGE,
  SELF_SERVICE_UNREACHABLE_MESSAGE,
  tooManyRequestsMessage,
} from '../utils/authMessages'
import { radius } from '../theme/tokens'

const HEADING = 'Passwort vergessen'

/**
 * Asks for a password-reset link. The acknowledgement is the same for an address with an account
 * and one without (ADR-0033, Entscheidung 11) - the page therefore has no failure case of its own
 * beyond a rejected entry and an unreachable backend. Where the installation does not offer the
 * flow, the page is not a page: it redirects, and the endpoint would answer 404 anyway.
 */
export default function ForgotPasswordPage() {
  const isLoading = useAuthStore((s) => s.isLoading)
  const minLength = useAuthStore((s) => s.localAccounts.passwordMinLength)
  const enabled = useAuthStore((s) => s.localAccounts.passwordResetEnabled)

  const [email, setEmail] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setFieldErrors({})
    setFormError(null)
    try {
      await forgotPassword(email.trim())
      setDone(true)
    } catch (err) {
      if (err instanceof FieldValidationError && err.fieldErrors.length > 0) {
        setFieldErrors(fieldErrorMessages(err.fieldErrors, minLength))
      } else if (err instanceof FieldValidationError) {
        setFormError(err.message)
      } else if (err instanceof RateLimitedError) {
        setFormError(tooManyRequestsMessage(err.retryAfterSeconds))
      } else if (err instanceof FlowUnavailableError) {
        setFormError(SELF_SERVICE_DISABLED_MESSAGE)
      } else {
        setFormError(SELF_SERVICE_UNREACHABLE_MESSAGE)
      }
    } finally {
      setBusy(false)
    }
  }

  // "Not known yet" is not "switched off": while the configuration is still loading nothing is
  // decided, or a reload of this page would bounce to the sign-in page before the answer arrives.
  if (isLoading) return null
  if (!enabled) return <Navigate to={LOGIN_ROUTE} replace />

  if (done) {
    return (
      <AuthLayout>
        <AuthNotice
          heading={HEADING}
          severity="success"
          message={MAIL_SENT_MESSAGE}
          detail="Bitte prüfen Sie Ihr Postfach. Der Link in der E-Mail gilt nur begrenzte Zeit; ein neuer Link macht einen älteren ungültig."
        >
          <Link component={RouterLink} to={LOGIN_ROUTE}>
            Zurück zur Anmeldung
          </Link>
        </AuthNotice>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout>
      <PageHeading title={HEADING} variant="h6" />
      {formError && (
        <Alert severity="error" sx={{ mt: 2, textAlign: 'left' }}>
          {formError}
        </Alert>
      )}
      <Box component="form" onSubmit={submit} noValidate sx={{ mt: 3 }}>
        <SectionEyebrow id="forgot-password-title">Link anfordern</SectionEyebrow>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 0.5, mb: 2 }}>
          Geben Sie die E-Mail-Adresse Ihres Kontos an. Wir schicken Ihnen einen Link, mit dem Sie
          ein neues Passwort festlegen können.
        </Typography>
        <Stack spacing={2}>
          <Box>
            <FieldLabel htmlFor="forgot-password-email">E-Mail-Adresse</FieldLabel>
            <TextField
              id="forgot-password-email"
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
            {busy ? 'Wird gesendet …' : 'Link anfordern'}
          </Button>
        </Stack>
      </Box>
      <Typography sx={{ mt: 3, fontSize: 12, textAlign: 'center' }}>
        <Link component={RouterLink} to={LOGIN_ROUTE} color="text.secondary">
          Zurück zur Anmeldung
        </Link>
      </Typography>
    </AuthLayout>
  )
}
