import { useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router'
import AuthLayout from '../components/auth/AuthLayout'
import AuthLoading from '../components/auth/AuthLoading'
import AuthNotice from '../components/auth/AuthNotice'
import NewPasswordField from '../components/auth/NewPasswordField'
import PasswordField from '../components/auth/PasswordField'
import SectionEyebrow from '../components/auth/SectionEyebrow'
import BrandMark from '../components/BrandMark'
import PageHeading from '../components/a11y/PageHeading'
import { useErrorFocus } from '../hooks/useErrorFocus'
import { useLinkToken } from '../hooks/useLinkToken'
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
/** The fields this form can show an error at, in the order the focus walks them. */
const FIELDS = ['newPassword', 'repeatedPassword'] as const

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
  // Reads the token and takes it out of the address bar at once, before any request of this page
  // can carry it in a referrer. A reload therefore has no token any more and reads as an invalid
  // link - see useLinkToken for why that is the cheaper consequence.
  const token = useLinkToken()
  const isLoading = useAuthStore((s) => s.isLoading)
  const minLength = useAuthStore((s) => s.localAccounts.passwordMinLength)
  const passwordResetEnabled = useAuthStore((s) => s.localAccounts.passwordResetEnabled)
  const sessionKind = useAuthStore((s) => s.sessionKind)
  const expireSession = useAuthStore((s) => s.expireSession)

  const [password, setPasswordValue] = useState('')
  const [repeated, setRepeated] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  const [linkInvalid, setLinkInvalid] = useState(false)
  const newPasswordRef = useRef<HTMLInputElement>(null)
  const repeatedRef = useRef<HTMLInputElement>(null)
  const alertRef = useRef<HTMLDivElement>(null)
  const focusFirstError = useErrorFocus(
    FIELDS,
    { newPassword: newPasswordRef, repeatedPassword: repeatedRef },
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
    if (password !== repeated) {
      failWith({ repeatedPassword: MISMATCH_MESSAGE }, null)
      return
    }
    setBusy(true)
    setFieldErrors({})
    setFormError(null)
    try {
      await setPassword(token, password)
      // The backend revoked every session of the account when it accepted the link. A tab that
      // still held a local one would otherwise follow "Zur Anmeldung" into the application with a
      // dead token; ending it here makes the link land on the sign-in page, with the reason.
      if (sessionKind === 'local') expireSession('session_revoked:password_changed')
      setDone(true)
    } catch (err) {
      if (err instanceof LinkInvalidError) {
        setLinkInvalid(true)
      } else if (err instanceof FieldValidationError && err.fieldErrors.length > 0) {
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

  if (done) {
    return (
      <AuthLayout>
        <AuthNotice heading={HEADING} severity="success" message={PASSWORD_SET_MESSAGE}>
          <SignInLink />
        </AuthNotice>
      </AuthLayout>
    )
  }

  // Both branches below read the configuration: the policy the form shows, and whether there is a
  // self-service way to a new link. Neither may be answered from the switched-off default while
  // the configuration is still on its way.
  if (isLoading) {
    return (
      <AuthLayout>
        <AuthLoading heading={HEADING} />
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
              ? 'Fordern Sie einen neuen Link an, oder wenden Sie sich an Ihre Systemverwaltung. Ein erneutes Laden dieser Seite hilft nicht — öffnen Sie den Link aus der E-Mail noch einmal.'
              : 'Bitte wenden Sie sich an Ihre Systemverwaltung; sie kann Ihnen einen neuen Link schicken. Ein erneutes Laden dieser Seite hilft nicht — öffnen Sie den Link aus der E-Mail noch einmal.'
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
      {/* The house's mark on a page reached cold from a mail, so the person can tell whose
          installation asks them for a password (#583, guidelines 7). Deliberately without heading
          semantics - the PageHeading below is this page's one h1. */}
      <Box sx={{ mb: 3 }}>
        <BrandMark orientation="vertical" variant="h6" logoHeight={32} />
      </Box>
      <PageHeading title={HEADING} variant="h6" />
      {formError && (
        <Alert ref={alertRef} tabIndex={-1} severity="error" sx={{ mt: 2, textAlign: 'left' }}>
          {formError}
        </Alert>
      )}
      <Box
        component="form"
        aria-labelledby="set-password-title"
        onSubmit={submit}
        noValidate
        sx={{ mt: 3 }}
      >
        <SectionEyebrow id="set-password-title">Neues Passwort wählen</SectionEyebrow>
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
            inputRef={newPasswordRef}
          />
          <PasswordField
            id="set-password-repeat"
            label="Neues Passwort wiederholen"
            value={repeated}
            onChange={setRepeated}
            autoComplete="new-password"
            disabled={busy}
            errorMessage={fieldErrors.repeatedPassword}
            inputRef={repeatedRef}
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
