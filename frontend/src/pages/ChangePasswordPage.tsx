import { useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { Navigate, Link as RouterLink, useLocation, useNavigate } from 'react-router'
import FieldLabel from '../components/wizard/FieldLabel'
import AuthLayout from '../components/auth/AuthLayout'
import SectionEyebrow from '../components/auth/SectionEyebrow'
import PageHeading from '../components/a11y/PageHeading'
import { FieldValidationError } from '../services/authApi'
import { useAuthStore } from '../stores/authStore'
import { notify } from '../stores/notificationStore'
import { AFTER_SIGN_IN_ROUTE, LOGIN_ROUTE } from '../routes'
import { passwordChangeReasonMessage, passwordFieldErrorMessage } from '../utils/authMessages'
import { redirectTargetOf } from '../utils/safeRedirectPath'
import { radius } from '../theme/tokens'

const MISMATCH_MESSAGE = 'Die beiden Eingaben stimmen nicht überein.'
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

  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [repeatedPassword, setRepeatedPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const currentRef = useRef<HTMLInputElement>(null)

  if (isLoading) return null
  if (!isAuthenticated) return <Navigate to={LOGIN_ROUTE} replace />

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (busy) return
    if (newPassword !== repeatedPassword) {
      setFieldErrors({ repeatedPassword: MISMATCH_MESSAGE })
      setFormError(null)
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
      if (err instanceof FieldValidationError && err.fieldErrors.length > 0) {
        setFieldErrors(
          Object.fromEntries(
            err.fieldErrors.map((entry) => [
              entry.field,
              passwordFieldErrorMessage(entry.code, minLength),
            ]),
          ),
        )
      } else if (err instanceof FieldValidationError) {
        setFormError(err.message)
      } else {
        setFormError(UNAVAILABLE_MESSAGE)
      }
      setCurrentPassword('')
      currentRef.current?.focus()
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
        <Alert severity="error" sx={{ mt: 2, textAlign: 'left' }}>
          {formError}
        </Alert>
      )}
      <Box component="form" onSubmit={submit} noValidate sx={{ mt: 3 }}>
        <SectionEyebrow id="change-password-title">Neues Passwort</SectionEyebrow>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 0.5, mb: 2 }}>
          {`Mindestens ${minLength} Zeichen, höchstens 64. Das Passwort darf nicht Ihrer E-Mail-Adresse entsprechen und nicht auf der Liste besonders häufiger Passwörter stehen.`}
        </Typography>
        <Stack spacing={2}>
          <Box>
            <FieldLabel htmlFor="change-password-current">Aktuelles Passwort</FieldLabel>
            <TextField
              id="change-password-current"
              inputRef={currentRef}
              type="password"
              size="small"
              fullWidth
              autoComplete="current-password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              disabled={busy}
              error={Boolean(fieldErrors.currentPassword)}
              helperText={fieldErrors.currentPassword}
            />
          </Box>
          <Box>
            <FieldLabel htmlFor="change-password-new">Neues Passwort</FieldLabel>
            <TextField
              id="change-password-new"
              type="password"
              size="small"
              fullWidth
              autoComplete="new-password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              disabled={busy}
              error={Boolean(fieldErrors.newPassword)}
              helperText={fieldErrors.newPassword}
            />
          </Box>
          <Box>
            <FieldLabel htmlFor="change-password-repeat">Neues Passwort wiederholen</FieldLabel>
            <TextField
              id="change-password-repeat"
              type="password"
              size="small"
              fullWidth
              autoComplete="new-password"
              value={repeatedPassword}
              onChange={(e) => setRepeatedPassword(e.target.value)}
              disabled={busy}
              error={Boolean(fieldErrors.repeatedPassword)}
              helperText={fieldErrors.repeatedPassword}
            />
          </Box>
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
    </AuthLayout>
  )
}
