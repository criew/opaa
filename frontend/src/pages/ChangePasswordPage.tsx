import { useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Navigate, Link as RouterLink, useLocation, useNavigate } from 'react-router'
import AuthLayout from '../components/auth/AuthLayout'
import NewPasswordField from '../components/auth/NewPasswordField'
import PasswordField from '../components/auth/PasswordField'
import SectionEyebrow from '../components/auth/SectionEyebrow'
import PageHeading from '../components/a11y/PageHeading'
import { useErrorFocus } from '../hooks/useErrorFocus'
import { FieldValidationError } from '../services/authApi'
import { useAuthStore } from '../stores/authStore'
import { notify } from '../stores/notificationStore'
import { AFTER_SIGN_IN_ROUTE, LOGIN_ROUTE } from '../routes'
import {
  fieldErrorMessages,
  passwordChangeReasonMessage,
  passwordPolicyText,
} from '../utils/authMessages'
import { redirectTargetOf } from '../utils/safeRedirectPath'
import { radius } from '../theme/tokens'

const MISMATCH_MESSAGE = 'Die beiden Eingaben stimmen nicht überein.'
/** The fields this form can show an error at, in the order the focus walks them. */
const FIELDS = ['currentPassword', 'newPassword', 'repeatedPassword'] as const
const UNAVAILABLE_MESSAGE =
  'Das Passwort konnte nicht geändert werden. Bitte versuchen Sie es erneut.'

/**
 * Sets a new password for an account of this installation - the way out of a forced change
 * (ADR-0033, Entscheidung 8) and, from the user settings, the voluntary one. The backend answers a
 * successful change with a session of its own, which the store adopts, so the change ends with a
 * working session rather than a new sign-in.
 */
export default function ChangePasswordPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const isLoading = useAuthStore((s) => s.isLoading)
  const sessionKind = useAuthStore((s) => s.sessionKind)
  const passwordChangeRequired = useAuthStore((s) => s.passwordChangeRequired)
  const passwordChangeReason = useAuthStore((s) => s.passwordChangeReason)
  const minLength = useAuthStore((s) => s.localAccounts.passwordMinLength)
  const changePassword = useAuthStore((s) => s.changePassword)
  const logout = useAuthStore((s) => s.logout)

  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [repeatedPassword, setRepeatedPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const currentRef = useRef<HTMLInputElement>(null)
  const newRef = useRef<HTMLInputElement>(null)
  const repeatedRef = useRef<HTMLInputElement>(null)
  const alertRef = useRef<HTMLDivElement>(null)
  const focusFirstError = useErrorFocus(
    FIELDS,
    { currentPassword: currentRef, newPassword: newRef, repeatedPassword: repeatedRef },
    alertRef,
  )

  if (isLoading) return null
  if (!isAuthenticated) return <Navigate to={LOGIN_ROUTE} replace />

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (busy) return
    if (newPassword !== repeatedPassword) {
      setFieldErrors({ repeatedPassword: MISMATCH_MESSAGE })
      setFormError(null)
      focusFirstError({ repeatedPassword: MISMATCH_MESSAGE })
      return
    }
    setBusy(true)
    setFieldErrors({})
    setFormError(null)
    try {
      await changePassword(currentPassword, newPassword)
      notify('Ihr Passwort wurde geändert.', 'success')
      navigate(redirectTargetOf(location.state, location.search), { replace: true })
    } catch (err) {
      let errors: Record<string, string> = {}
      if (err instanceof FieldValidationError && err.fieldErrors.length > 0) {
        const { byField, unassigned } = fieldErrorMessages(err.fieldErrors, minLength, FIELDS)
        errors = byField
        setFieldErrors(byField)
        setFormError(unassigned.length > 0 ? unassigned.join(' ') : null)
      } else if (err instanceof FieldValidationError) {
        setFormError(err.message)
      } else {
        setFormError(UNAVAILABLE_MESSAGE)
      }
      // The current password is cleared only when it was the thing refused; clearing it after a
      // rejected *new* password would make the person retype what was already right.
      if (errors.currentPassword) setCurrentPassword('')
      focusFirstError(errors)
    } finally {
      setBusy(false)
    }
  }

  if (sessionKind !== 'local') {
    return (
      <AuthLayout>
        <PageHeading title="Passwort ändern" variant="h6" />
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 1 }}>
          Das Passwort dieses Kontos verwaltet Ihr Identitätsanbieter. Ändern Sie es dort.
        </Typography>
        <Typography sx={{ mt: 3, fontSize: 13 }}>
          <Link component={RouterLink} to={AFTER_SIGN_IN_ROUTE}>
            Zurück zur Anwendung
          </Link>
        </Typography>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout>
      <PageHeading title="Passwort ändern" variant="h6" />
      {passwordChangeRequired && (
        <Alert severity="info" sx={{ mt: 2, textAlign: 'left' }}>
          <AlertTitle>Neues Passwort erforderlich</AlertTitle>
          {passwordChangeReasonMessage(passwordChangeReason)}
        </Alert>
      )}
      {formError && (
        <Alert ref={alertRef} tabIndex={-1} severity="error" sx={{ mt: 2, textAlign: 'left' }}>
          {formError}
        </Alert>
      )}
      <Box
        component="form"
        aria-labelledby="change-password-title"
        onSubmit={submit}
        noValidate
        sx={{ mt: 3 }}
      >
        <SectionEyebrow id="change-password-title">Neues Passwort wählen</SectionEyebrow>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 0.5, mb: 2 }}>
          {passwordPolicyText(minLength)}
        </Typography>
        <Stack spacing={2}>
          <PasswordField
            id="change-password-current"
            label="Aktuelles Passwort"
            value={currentPassword}
            onChange={setCurrentPassword}
            autoComplete="current-password"
            disabled={busy}
            errorMessage={fieldErrors.currentPassword}
            inputRef={currentRef}
          />
          <NewPasswordField
            id="change-password-new"
            label="Neues Passwort"
            value={newPassword}
            onChange={setNewPassword}
            minLength={minLength}
            disabled={busy}
            errorMessage={fieldErrors.newPassword}
            onGenerated={setRepeatedPassword}
            inputRef={newRef}
          />
          <PasswordField
            id="change-password-repeat"
            label="Neues Passwort wiederholen"
            value={repeatedPassword}
            onChange={setRepeatedPassword}
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
            {busy ? 'Wird gespeichert …' : 'Passwort speichern'}
          </Button>
        </Stack>
      </Box>
      {/* A forced change locks every other route; without this the only way out would be closing
          the browser. */}
      <Typography sx={{ mt: 3, fontSize: 12, textAlign: 'center' }}>
        <Link
          component="button"
          type="button"
          onClick={() => void logout()}
          color="text.secondary"
          sx={{ fontSize: 12 }}
        >
          Abmelden
        </Link>
      </Typography>
    </AuthLayout>
  )
}
