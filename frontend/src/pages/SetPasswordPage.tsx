import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Link as RouterLink, useSearchParams } from 'react-router'
import AuthLayout from '../components/auth/AuthLayout'
import AuthNotice from '../components/auth/AuthNotice'
import NewPasswordField from '../components/auth/NewPasswordField'
import PasswordField from '../components/auth/PasswordField'
import SectionEyebrow from '../components/auth/SectionEyebrow'
import PageHeading from '../components/a11y/PageHeading'
import { FieldValidationError } from '../services/authApi'
import {
  FlowUnavailableError,
  LinkInvalidError,
  RateLimitedError,
  setPassword,
} from '../services/selfServiceApi'
import { useAuthStore } from '../stores/authStore'
import { FORGOT_PASSWORD_ROUTE, LOGIN_ROUTE } from '../routes'
import {
  fieldErrorMessages,
  LINK_INVALID_MESSAGE,
  PASSWORD_SET_MESSAGE,
  passwordPolicyText,
  SELF_SERVICE_DISABLED_MESSAGE,
  SELF_SERVICE_UNREACHABLE_MESSAGE,
  tooManyRequestsMessage,
} from '../utils/authMessages'
import { radius } from '../theme/tokens'

const HEADING = 'Passwort festlegen'
const MISMATCH_MESSAGE = 'Die beiden Eingaben stimmen nicht überein.'

function SignInLink({ children = 'Zur Anmeldung' }: { children?: string }) {
  return (
    <Link component={RouterLink} to={LOGIN_ROUTE}>
      {children}
    </Link>
  )
}

/**
 * Redeems the link of an invitation or of a password reset - one page for both, with a heading that
 * says neither (ADR-0033, Entscheidung 11): the backend treats the two purposes alike, and a
 * heading that guessed wrong would contradict the mail the person is holding. Reachable at all
 * times, even with the self-service switches off: a link an administrator handed out must not run
 * into a redirect.
 */
export default function SetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const minLength = useAuthStore((s) => s.localAccounts.passwordMinLength)
  const passwordResetEnabled = useAuthStore((s) => s.localAccounts.passwordResetEnabled)

  const [password, setPasswordValue] = useState('')
  const [repeated, setRepeated] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  const [linkInvalid, setLinkInvalid] = useState(false)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (busy) return
    if (password !== repeated) {
      setFieldErrors({ repeatedPassword: MISMATCH_MESSAGE })
      setFormError(null)
      return
    }
    setBusy(true)
    setFieldErrors({})
    setFormError(null)
    try {
      await setPassword(token, password)
      setDone(true)
    } catch (err) {
      if (err instanceof LinkInvalidError) {
        setLinkInvalid(true)
      } else if (err instanceof FieldValidationError && err.fieldErrors.length > 0) {
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

  if (done) {
    return (
      <AuthLayout>
        <AuthNotice heading={HEADING} severity="success" message={PASSWORD_SET_MESSAGE}>
          <SignInLink />
        </AuthNotice>
      </AuthLayout>
    )
  }

  // A missing token and a refused one read the same: both mean "this link does not work", and a
  // person who opened a truncated link is in exactly the same position as one with an expired one.
  if (linkInvalid || token === '') {
    return (
      <AuthLayout>
        <AuthNotice
          heading={HEADING}
          severity="error"
          message={LINK_INVALID_MESSAGE}
          detail={
            passwordResetEnabled
              ? 'Fordern Sie einen neuen Link an, oder wenden Sie sich an Ihre Systemverwaltung.'
              : 'Bitte wenden Sie sich an Ihre Systemverwaltung; sie kann Ihnen einen neuen Link schicken.'
          }
        >
          {passwordResetEnabled ? (
            <Link component={RouterLink} to={FORGOT_PASSWORD_ROUTE}>
              Neuen Link anfordern
            </Link>
          ) : (
            <SignInLink />
          )}
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
        <SectionEyebrow id="set-password-title">Neues Passwort</SectionEyebrow>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 0.5, mb: 2 }}>
          {passwordPolicyText(minLength)}
        </Typography>
        <Stack spacing={2}>
          <NewPasswordField
            id="set-password-new"
            label="Neues Passwort"
            value={password}
            onChange={setPasswordValue}
            minLength={minLength}
            disabled={busy}
            errorMessage={fieldErrors.newPassword}
            onGenerated={setRepeated}
          />
          <PasswordField
            id="set-password-repeat"
            label="Neues Passwort wiederholen"
            value={repeated}
            onChange={setRepeated}
            autoComplete="new-password"
            disabled={busy}
            errorMessage={fieldErrors.repeatedPassword}
          />
          <Button
            type="submit"
            variant="contained"
            disabled={busy}
            sx={{ alignSelf: 'flex-start', borderRadius: `${radius.md}px`, minWidth: 140 }}
          >
            {busy ? 'Wird gespeichert …' : 'Passwort festlegen'}
          </Button>
        </Stack>
      </Box>
      <Typography sx={{ mt: 3, fontSize: 12, textAlign: 'center' }}>
        <SignInLink>Zurück zur Anmeldung</SignInLink>
      </Typography>
    </AuthLayout>
  )
}
