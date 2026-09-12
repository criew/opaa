import { useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import { Link as RouterLink, useSearchParams } from 'react-router'
import AuthLayout from '../components/auth/AuthLayout'
import AuthNotice from '../components/auth/AuthNotice'
import PageHeading from '../components/a11y/PageHeading'
import { RateLimitedError, verifyEmail } from '../services/selfServiceApi'
import { LOGIN_ROUTE } from '../routes'
import {
  EMAIL_VERIFIED_MESSAGE,
  LINK_INVALID_MESSAGE,
  tooManyRequestsMessage,
} from '../utils/authMessages'

const HEADING = 'E-Mail-Adresse bestätigen'

type Status = 'checking' | 'confirmed' | 'invalid' | 'rateLimited'

/**
 * The page behind the verification link. The link itself is a plain `GET`; the confirming `POST`
 * happens here - and exactly once, which the ref guards: React's StrictMode mounts an effect twice
 * in development, and the token is single-use, so the second call would consume nothing and report
 * the link as invalid right after it worked.
 */
export default function VerifyEmailPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const [status, setStatus] = useState<Status>(token === '' ? 'invalid' : 'checking')
  const [waitMessage, setWaitMessage] = useState<string | null>(null)
  const sent = useRef(false)

  useEffect(() => {
    if (token === '' || sent.current) return
    sent.current = true
    verifyEmail(token).then(
      () => setStatus('confirmed'),
      (err: unknown) => {
        // A refused rate limit is the one failure that says "try again in a moment" rather than
        // "this link is gone" - the link is untouched, so offering it as invalid would be wrong.
        // Every other failure reads the same: an invalid token and an unreachable backend both
        // leave the address unconfirmed, and the way on is identical.
        if (err instanceof RateLimitedError) {
          setWaitMessage(tooManyRequestsMessage(err.retryAfterSeconds))
          setStatus('rateLimited')
          return
        }
        setStatus('invalid')
      },
    )
  }, [token])

  if (status === 'checking') {
    return (
      <AuthLayout>
        <PageHeading title={HEADING} variant="h6" />
        <Box
          sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mt: 3, color: 'text.secondary' }}
        >
          <CircularProgress size={20} aria-hidden="true" />
          <Typography role="status" sx={{ fontSize: 13.5 }}>
            Ihre E-Mail-Adresse wird geprüft …
          </Typography>
        </Box>
      </AuthLayout>
    )
  }

  if (status === 'rateLimited') {
    return (
      <AuthLayout>
        <AuthNotice
          heading={HEADING}
          severity="warning"
          message={waitMessage ?? tooManyRequestsMessage(null)}
          detail="Ihr Link ist dadurch nicht verbraucht - öffnen Sie ihn danach einfach erneut."
        >
          <Link component={RouterLink} to={LOGIN_ROUTE}>
            Zur Anmeldung
          </Link>
        </AuthNotice>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout>
      <AuthNotice
        heading={HEADING}
        severity={status === 'confirmed' ? 'success' : 'error'}
        message={status === 'confirmed' ? EMAIL_VERIFIED_MESSAGE : LINK_INVALID_MESSAGE}
        detail={
          status === 'confirmed'
            ? undefined
            : 'Der Link gilt 24 Stunden und nur einmal. Registrieren Sie sich erneut, wenn er abgelaufen ist.'
        }
      >
        <Link component={RouterLink} to={LOGIN_ROUTE}>
          Zur Anmeldung
        </Link>
      </AuthNotice>
    </AuthLayout>
  )
}
