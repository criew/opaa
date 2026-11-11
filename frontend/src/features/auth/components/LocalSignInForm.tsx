import { useId, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router'
import FormField from '@/shared/ui/form/FormField'
import SecretField from '@/shared/ui/form/SecretField'
import SectionHead from '@/shared/ui/SectionHead'
import { TextLink } from '@/shared/ui/DetailSection'
import { useAuthStore } from '@/shared/session/authStore'
import { FORGOT_PASSWORD_ROUTE, REGISTER_ROUTE } from '@/shared/routes'
import { fontSize, radius } from '@/shared/theme/tokens'

interface LocalSignInFormProps {
  /** Head of the section; also the accessible name of the form. */
  title: string
  /** One sentence above the fields saying whose accounts these are. */
  description: string
  /** The self-service links the configuration allows (ADR-0033, Entscheidung 11). */
  showPasswordReset?: boolean
  showSelfRegistration?: boolean
  /** Another sign-in is already under way on this page. */
  disabled?: boolean
  /** Whether its submit button is the page's one primary button; secondary below a provider. */
  emphasised?: boolean
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
  emphasised = true,
}: LocalSignInFormProps) {
  const idPrefix = useId()
  const emailId = `${idPrefix}-email`
  const passwordId = `${idPrefix}-password`
  const titleId = `${idPrefix}-title`
  const loginLocal = useAuthStore((s) => s.loginLocal)
  const isSigningIn = useAuthStore((s) => s.isSigningIn)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
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
      <SectionHead id={titleId} underline={false}>
        {title}
      </SectionHead>
      <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', mt: 0.5, mb: 2 }}>
        {description}
      </Typography>
      <Stack spacing={2}>
        <FormField
          id={emailId}
          label="E-Mail-Adresse"
          inputRef={emailRef}
          type="email"
          size="small"
          autoComplete="username"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          disabled={busy}
        />
        <SecretField
          id={passwordId}
          label="Passwort"
          value={password}
          onChange={setPassword}
          autoComplete="current-password"
          disabled={busy}
        />
        <Stack
          direction="row"
          spacing={1.5}
          sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}
        >
          <Button
            type="submit"
            variant={emphasised ? 'contained' : 'outlined'}
            disabled={busy}
            sx={{ borderRadius: `${radius.md}px`, minWidth: 140 }}
          >
            {isSigningIn ? 'Anmeldung läuft …' : 'Anmelden'}
          </Button>
          {showPasswordReset && <TextLink to={FORGOT_PASSWORD_ROUTE}>Passwort vergessen?</TextLink>}
        </Stack>
        {showSelfRegistration && (
          <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary' }}>
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
