import { useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router'
import AuthLayout from '../components/auth/AuthLayout'
import AuthNotice from '../components/auth/AuthNotice'
import BrandMark from '../components/BrandMark'
import PageHeading from '../components/a11y/PageHeading'
import { useLinkToken } from '../hooks/useLinkToken'
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
 * The house's mark on a page reached cold from a mail, so the person can tell whose installation
 * they are confirming an address for (#583, guidelines 7). Deliberately without heading semantics -
 * the page's one h1 follows below.
 */
function BrandHead() {
  return (
    <Box sx={{ mb: 3 }}>
      <BrandMark orientation="vertical" variant="h6" logoHeight={32} />
    </Box>
  )
}

/**
 * The page behind the verification link. The link itself is a plain `GET`; the confirming `POST`
 * happens here - and exactly once per mount, which the ref guards: React's StrictMode runs the
 * effect twice in development, and the token is single-use, so the second call would consume
 * nothing and report the link as invalid right after it worked. The ref holds the token rather than
 * a flag, so a mount whose token changed would still send - the guard is "this token has been
 * sent", not "some request has gone out".
 */
export default function VerifyEmailPage() {
  // Reads the token and takes it out of the address bar before this page's first request leaves, so
  // no referrer carries it. A reload then has no token and reads as an invalid link - the link from
  // the mail is the way back (see useLinkToken).
  const token = useLinkToken()
  const [status, setStatus] = useState<Status>(token === '' ? 'invalid' : 'checking')
  const [waitMessage, setWaitMessage] = useState<string | null>(null)
  const sentToken = useRef<string | null>(null)

  useEffect(() => {
    if (token === '' || sentToken.current === token) return
    sentToken.current = token
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
        <BrandHead />
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
        <BrandHead />
        <AuthNotice
          heading={HEADING}
          severity="warning"
          message={waitMessage ?? tooManyRequestsMessage(null)}
          detail="Ihr Link ist dadurch nicht verbraucht — öffnen Sie ihn danach einfach erneut."
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
      <BrandHead />
      <AuthNotice
        heading={HEADING}
        severity={status === 'confirmed' ? 'success' : 'error'}
        message={status === 'confirmed' ? EMAIL_VERIFIED_MESSAGE : LINK_INVALID_MESSAGE}
        detail={
          status === 'confirmed'
            ? undefined
            : 'Der Link gilt 24 Stunden und nur einmal. Ein erneutes Laden dieser Seite hilft nicht — öffnen Sie den Link aus der E-Mail noch einmal, oder registrieren Sie sich erneut, wenn er abgelaufen ist.'
        }
      >
        <Link component={RouterLink} to={LOGIN_ROUTE}>
          Zur Anmeldung
        </Link>
      </AuthNotice>
    </AuthLayout>
  )
}
