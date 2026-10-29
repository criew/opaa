import { useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router'
import AuthLayout from '../components/AuthLayout'
import AuthLoading from '../components/AuthLoading'
import AuthNotice from '../components/AuthNotice'
import NewPasswordField from '../components/NewPasswordField'
import PasswordField from '../components/PasswordField'
import SectionEyebrow from '../components/SectionEyebrow'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import { useErrorFocus } from '@/shared/hooks/useErrorFocus'
import { useLinkToken } from '../hooks/useLinkToken'
import { FieldValidationError } from '@/shared/session/authApi'
import {
  confirmRegistration,
  FlowUnavailableError,
  LinkInvalidError,
  RateLimitedError,
} from '../api/selfServiceApi'
import { useAuthStore } from '@/shared/session/authStore'
import { LOGIN_ROUTE } from '@/shared/routes'
import {
  EMAIL_VERIFIED_MESSAGE,
  fieldErrorMessages,
  LINK_INVALID_MESSAGE,
  passwordPolicyText,
  SELF_SERVICE_UNREACHABLE_MESSAGE,
  tooManyRequestsMessage,
} from '@/shared/session/authMessages'
import { radius } from '@/shared/theme/tokens'

const HEADING = 'Passwort festlegen'
const MISMATCH_MESSAGE = 'Die beiden Eingaben stimmen nicht überein.'
/** The fields this form can show an error at, in the order the focus walks them. */
const FIELDS = ['displayName', 'newPassword', 'repeatedPassword'] as const
/** A backend that does not know the endpoint yet (rolling update); the link is untouched. */
const NOT_YET_AVAILABLE_MESSAGE =
  'Die Bestätigung ist gerade nicht möglich. Ihr Link ist dadurch nicht verbraucht — versuchen Sie es in einigen Minuten erneut.'

function SignInLink({ children = 'Zur Anmeldung' }: { children?: string }) {
  return (
    <Link component={RouterLink} to={LOGIN_ROUTE}>
      {children}
    </Link>
  )
}

/**
 * The page behind the verification link of a self-registration (ADR-0033, Entscheidung 11). The
 * registration carries no password and only a provisional name: the person chooses both here, and
 * the one request confirms the address and sets them together - so only who holds the mailbox
 * decides them. The link
 * itself is a plain `GET`; nothing is sent before the form is submitted, and a refused password
 * leaves the link open. Reachable whatever the self-service switches say.
 */
export default function VerifyEmailPage() {
  // Reads the token and takes it out of the address bar before this page's first request leaves, so
  // no referrer carries it. A reload then has no token and reads as an invalid link - the link from
  // the mail is the way back (see useLinkToken).
  const token = useLinkToken()
  const isLoading = useAuthStore((s) => s.isLoading)
  const minLength = useAuthStore((s) => s.localAccounts.passwordMinLength)
  const sessionKind = useAuthStore((s) => s.sessionKind)
  const expireSession = useAuthStore((s) => s.expireSession)

  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [repeated, setRepeated] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  const [linkInvalid, setLinkInvalid] = useState(false)
  const displayNameRef = useRef<HTMLInputElement>(null)
  const newPasswordRef = useRef<HTMLInputElement>(null)
  const repeatedRef = useRef<HTMLInputElement>(null)
  const alertRef = useRef<HTMLDivElement>(null)
  const focusFirstError = useErrorFocus(
    FIELDS,
    { displayName: displayNameRef, newPassword: newPasswordRef, repeatedPassword: repeatedRef },
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
      await confirmRegistration(token, displayName.trim(), password)
      // The backend ended every session of the account; a local one still held by this tab would
      // otherwise follow "Zur Anmeldung" into the application with a dead token.
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
        // The link is untouched by a refused rate limit - the form stays, with the wait.
        failWith({}, tooManyRequestsMessage(err.retryAfterSeconds))
      } else if (err instanceof FlowUnavailableError) {
        failWith({}, NOT_YET_AVAILABLE_MESSAGE)
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
        <AuthNotice heading={HEADING} severity="success" message={EMAIL_VERIFIED_MESSAGE}>
          <SignInLink />
        </AuthNotice>
      </AuthLayout>
    )
  }

  // A missing token and a refused one read the same: both mean "this link does not work".
  if (linkInvalid || token === '') {
    return (
      <AuthLayout>
        <AuthNotice
          heading={HEADING}
          severity="error"
          message={LINK_INVALID_MESSAGE}
          detail="Der Link gilt 24 Stunden und nur einmal. Ein erneutes Laden dieser Seite hilft nicht — öffnen Sie den Link aus der E-Mail noch einmal, oder registrieren Sie sich erneut, wenn er abgelaufen ist."
        >
          <SignInLink />
        </AuthNotice>
      </AuthLayout>
    )
  }

  // The form shows the policy from the configuration; it must not show the default while that is
  // still on its way.
  if (isLoading) {
    return (
      <AuthLayout>
        <AuthLoading heading={HEADING} />
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
        aria-labelledby="verify-email-title"
        onSubmit={submit}
        noValidate
        sx={{ mt: 3 }}
      >
        <SectionEyebrow id="verify-email-title">Zugang einrichten</SectionEyebrow>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 0.5, mb: 2 }}>
          Mit Name und Passwort bestätigen Sie zugleich Ihre E-Mail-Adresse.
        </Typography>
        <Stack spacing={2}>
          <Box>
            <FieldLabel htmlFor="verify-email-display-name">Name</FieldLabel>
            <TextField
              id="verify-email-display-name"
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
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
            {passwordPolicyText(minLength)}
          </Typography>
          <NewPasswordField
            id="verify-email-new"
            label="Passwort"
            value={password}
            onChange={setPassword}
            minLength={minLength}
            disabled={busy}
            errorMessage={fieldErrors.newPassword}
            onGenerated={setRepeated}
            inputRef={newPasswordRef}
          />
          <PasswordField
            id="verify-email-repeat"
            label="Passwort wiederholen"
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
            {busy ? 'Wird gespeichert …' : 'Adresse bestätigen'}
          </Button>
        </Stack>
      </Box>
      <Typography sx={{ mt: 3, fontSize: 12, textAlign: 'center' }}>
        <SignInLink>Zurück zur Anmeldung</SignInLink>
      </Typography>
    </AuthLayout>
  )
}
