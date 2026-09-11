import { useId, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import VisibilityOffOutlinedIcon from '@mui/icons-material/VisibilityOffOutlined'
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined'
import { Link as RouterLink } from 'react-router'
import FieldLabel from '../wizard/FieldLabel'
import SectionEyebrow from './SectionEyebrow'
import { useAuthStore } from '../../stores/authStore'
import { FORGOT_PASSWORD_ROUTE, REGISTER_ROUTE } from '../../routes'
import { radius } from '../../theme/tokens'

interface LocalSignInFormProps {
  /** Eyebrow of the section; also the accessible name of the form. */
  title: string
  /** One sentence above the fields saying whose accounts these are. */
  description: string
  /** The self-service links the configuration allows (ADR-0033, Entscheidung 11). */
  showPasswordReset?: boolean
  showSelfRegistration?: boolean
  /** Another sign-in is already under way on this page. */
  disabled?: boolean
}

/**
 * The password mask of accounts of this installation, shared by the sign-in page and the system
 * administrators' sign-in. Every refusal reads the same (ADR-0033, Entscheidung 9); the wording
 * comes from the store and appears in the card's one alert, after which the focus returns to the
 * first field so a correction can start straight away.
 */
export default function LocalSignInForm({
  title,
  description,
  showPasswordReset = false,
  showSelfRegistration = false,
  disabled = false,
}: LocalSignInFormProps) {
  const idPrefix = useId()
  const emailId = `${idPrefix}-email`
  const passwordId = `${idPrefix}-password`
  const titleId = `${idPrefix}-title`
  const loginLocal = useAuthStore((s) => s.loginLocal)
  const isSigningIn = useAuthStore((s) => s.isSigningIn)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [passwordVisible, setPasswordVisible] = useState(false)
  const emailRef = useRef<HTMLInputElement>(null)
  const busy = isSigningIn || disabled

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (busy) return
    const ok = await loginLocal(email.trim(), password)
    if (!ok) {
      setPassword('')
      emailRef.current?.focus()
    }
  }

  return (
    <Box component="form" aria-labelledby={titleId} onSubmit={submit} noValidate>
      <SectionEyebrow id={titleId}>{title}</SectionEyebrow>
      <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 0.5, mb: 2 }}>
        {description}
      </Typography>
      <Stack spacing={2}>
        <Box>
          <FieldLabel htmlFor={emailId}>E-Mail-Adresse</FieldLabel>
          <TextField
            id={emailId}
            inputRef={emailRef}
            type="email"
            size="small"
            fullWidth
            autoComplete="username"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={busy}
          />
        </Box>
        <Box>
          <FieldLabel htmlFor={passwordId}>Passwort</FieldLabel>
          <TextField
            id={passwordId}
            type={passwordVisible ? 'text' : 'password'}
            size="small"
            fullWidth
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={busy}
            slotProps={{
              input: {
                endAdornment: (
                  <InputAdornment position="end">
                    <Tooltip title={passwordVisible ? 'Passwort verbergen' : 'Passwort anzeigen'}>
                      <IconButton
                        aria-label={passwordVisible ? 'Passwort verbergen' : 'Passwort anzeigen'}
                        edge="end"
                        size="small"
                        onClick={() => setPasswordVisible((visible) => !visible)}
                      >
                        {passwordVisible ? (
                          <VisibilityOffOutlinedIcon fontSize="small" />
                        ) : (
                          <VisibilityOutlinedIcon fontSize="small" />
                        )}
                      </IconButton>
                    </Tooltip>
                  </InputAdornment>
                ),
              },
            }}
          />
        </Box>
        <Stack
          direction="row"
          spacing={1.5}
          sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}
        >
          <Button
            type="submit"
            variant="contained"
            disabled={busy}
            sx={{ borderRadius: `${radius.md}px`, minWidth: 140 }}
          >
            {isSigningIn ? 'Anmeldung läuft …' : 'Anmelden'}
          </Button>
          {showPasswordReset && (
            <Link component={RouterLink} to={FORGOT_PASSWORD_ROUTE} sx={{ fontSize: 13 }}>
              Passwort vergessen?
            </Link>
          )}
        </Stack>
        {showSelfRegistration && (
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
            Noch kein Konto?{' '}
            <Link component={RouterLink} to={REGISTER_ROUTE}>
              Konto registrieren
            </Link>
          </Typography>
        )}
      </Stack>
    </Box>
  )
}
