import { useCallback, useState, type FormEvent } from 'react'
import { isAxiosError } from 'axios'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import { useAuthStore } from '../session/authStore'
import { FieldValidationError, retryAfterSecondsOf } from '../session/authApi'
import { tooManyAttemptsMessage } from '../session/authMessages'
import { apiErrorStatus } from '../api/apiErrorDetails'
import BusyButton from './a11y/BusyButton'
import FieldLabel from './wizard/FieldLabel'

/** Every refusal alike, as at the sign-in: wrong password, locked account, network policy. */
export const NOT_CONFIRMED_MESSAGE =
  'Die Anmeldung ließ sich nicht bestätigen. Bitte prüfen Sie Ihr Passwort.'
export const CONFIRMATION_FAILED_MESSAGE =
  'Die Anmeldung ließ sich nicht bestätigen. Bitte versuchen Sie es erneut.'
export const CONFIRMED_ANNOUNCEMENT = 'Anmeldung bestätigt.'
export const REDIRECT_ANNOUNCEMENT = 'Sie werden zu Ihrem Anmeldedienst weitergeleitet …'

interface RecentLoginDialogProps {
  open: boolean
  /** What the confirmation is for, e.g. "Bevor Sie Ihre Daten herunterladen, …". */
  purpose: string
  /**
   * The person confirmed; the operation that asked for it may run now. The caller may close the
   * dialog right away: the announcement lives outside it and stays until the next opening.
   */
  onConfirmed: () => void
  onClose: () => void
  /**
   * Where a sign-in forced at the provider returns to; the current route by default. The page
   * there learns of the return from `consumeRecentLoginReturn()`.
   */
  returnTo?: string
}

type Phase = 'idle' | 'checking' | 'redirecting' | 'confirmed'

function failureMessage(err: unknown): string {
  if (err instanceof FieldValidationError) return NOT_CONFIRMED_MESSAGE
  if (apiErrorStatus(err) === 429) return tooManyAttemptsMessage(retryAfterSecondsOf(err))
  // a sentence of our own (the provider of the session is gone) is shown as it is; a transport
  // failure carries an English library message and gets the general sentence
  return err instanceof Error && !isAxiosError(err) && err.message
    ? err.message
    : CONFIRMATION_FAILED_MESSAGE
}

/**
 * Confirms the person's identity before an operation that requires a recent sign-in: a local
 * session asks for the password, a provider session forwards to a sign-in forced at the provider,
 * and the development mode, which has no sign-in, only asks to continue. Usable by keyboard alone
 * (the dialog holds the focus, Enter submits); every change of state is announced in a polite
 * status region, a failure as an alert. Every opening starts afresh: no password, no error, no
 * earlier announcement.
 */
export default function RecentLoginDialog({
  open,
  purpose,
  onConfirmed,
  onClose,
  returnTo,
}: RecentLoginDialogProps) {
  const mode = useAuthStore((s) => s.mode)
  const sessionKind = useAuthStore((s) => s.sessionKind)
  const confirmSignInWithPassword = useAuthStore((s) => s.confirmSignInWithPassword)
  const confirmSignInAtProvider = useAuthStore((s) => s.confirmSignInAtProvider)
  const [password, setPassword] = useState('')
  const [phase, setPhase] = useState<Phase>('idle')
  const [error, setError] = useState<string | null>(null)
  const [wasOpen, setWasOpen] = useState(open)
  if (open !== wasOpen) {
    // reset while rendering the opening, so not even one frame shows the previous request
    setWasOpen(open)
    if (open) {
      setPassword('')
      setError(null)
      setPhase('idle')
    }
  }
  // the first control takes the focus once, when the dialog opens, never again on a re-render
  const focusOnMount = useCallback((node: HTMLElement | null) => node?.focus(), [])
  const busy = phase === 'checking' || phase === 'redirecting'
  const kind = mode === 'dev' ? 'dev' : sessionKind === 'local' ? 'local' : 'provider'

  function close() {
    if (busy) return
    setPassword('')
    setError(null)
    setPhase('idle')
    onClose()
  }

  function confirmed() {
    setPassword('')
    setPhase('confirmed')
    onConfirmed()
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setError(null)
    if (kind === 'dev') {
      confirmed()
      return
    }
    if (kind === 'local') {
      if (!password) {
        setError('Bitte geben Sie Ihr Passwort ein.')
        return
      }
      setPhase('checking')
      try {
        await confirmSignInWithPassword(password)
        confirmed()
      } catch (err) {
        setPassword('')
        setPhase('idle')
        setError(failureMessage(err))
      }
      return
    }
    setPhase('redirecting')
    try {
      await confirmSignInAtProvider(returnTo ?? window.location.pathname + window.location.search)
    } catch (err) {
      setPhase('idle')
      setError(failureMessage(err))
    }
  }

  const status =
    phase === 'checking'
      ? 'Passwort wird geprüft …'
      : phase === 'redirecting'
        ? REDIRECT_ANNOUNCEMENT
        : phase === 'confirmed'
          ? CONFIRMED_ANNOUNCEMENT
          : ''

  return (
    <>
      <Dialog
        open={open}
        onClose={close}
        maxWidth="xs"
        fullWidth
        aria-labelledby="recent-login-title"
        slotProps={{ paper: { component: 'form', onSubmit: handleSubmit } }}
      >
        <DialogTitle id="recent-login-title">Anmeldung bestätigen</DialogTitle>
        <DialogContent>
          <Stack spacing={2}>
            <Typography variant="body2">{purpose}</Typography>
            {kind === 'local' && (
              <Box>
                <FieldLabel htmlFor="recent-login-password">Ihr Passwort</FieldLabel>
                <TextField
                  id="recent-login-password"
                  type="password"
                  size="small"
                  fullWidth
                  inputRef={focusOnMount}
                  autoComplete="current-password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  error={Boolean(error)}
                />
              </Box>
            )}
            {kind === 'provider' && (
              <Typography variant="body2">
                Sie werden zu Ihrem Anmeldedienst weitergeleitet und melden sich dort erneut an.
                Danach kommen Sie hierher zurück.
              </Typography>
            )}
            {kind === 'dev' && (
              <Typography variant="body2">
                Im Entwicklungsmodus gibt es keine Anmeldung; jede Anfrage gilt als frisch
                angemeldet.
              </Typography>
            )}
            {error && <Alert severity="error">{error}</Alert>}
            <Box component="span" role="status" aria-live="polite" sx={visuallyHidden}>
              {status}
            </Box>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={close} aria-disabled={busy || undefined}>
            Abbrechen
          </Button>
          <BusyButton
            type="submit"
            variant="contained"
            busy={busy}
            busyAnnouncement=""
            ref={kind === 'local' ? undefined : focusOnMount}
          >
            {kind === 'provider' ? 'Erneut anmelden' : kind === 'dev' ? 'Fortfahren' : 'Bestätigen'}
          </BusyButton>
        </DialogActions>
      </Dialog>
      {/* The open modal hides the page from assistive technology, so the region inside it speaks
          while it is open; this one, always present, takes over the confirmation once a caller
          closes the dialog on it. */}
      <Box component="span" role="status" aria-live="polite" sx={visuallyHidden}>
        {!open && phase === 'confirmed' ? CONFIRMED_ANNOUNCEMENT : ''}
      </Box>
    </>
  )
}
