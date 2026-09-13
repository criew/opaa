import { useCallback, useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Link from '@mui/material/Link'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemText from '@mui/material/ListItemText'
import Typography from '@mui/material/Typography'
import { Link as RouterLink, useNavigate } from 'react-router'
import AuthLayout from '../components/auth/AuthLayout'
import AuthNotice from '../components/auth/AuthNotice'
import PageHeading from '../components/a11y/PageHeading'
import SectionEyebrow from '../components/auth/SectionEyebrow'
import { useLinkToken } from '../hooks/useLinkToken'
import { usePageTitle } from '../hooks/usePageTitle'
import {
  HandoverRefusedError,
  previewHandover,
  ProviderAccountExistsError,
  ProviderMismatchError,
  ProviderSignInInvalidError,
  redeemHandover,
} from '../services/handoverApi'
import { LinkInvalidError, RateLimitedError } from '../services/selfServiceApi'
import {
  clearPendingHandover,
  peekPendingHandover,
  type PendingHandover,
} from '../stores/handoverFlow'
import { useAuthStore } from '../stores/authStore'
import { AFTER_SIGN_IN_ROUTE, LOGIN_ROUTE } from '../routes'
import type { LocalHandoverPreviewResponse } from '../types/api'
import { LINK_INVALID_MESSAGE, tooManyRequestsMessage } from '../utils/authMessages'

const HEADING = 'Zugang an Ihre Anbieteridentität übergeben'

const SYSTEM_ROLE_TEXT: Record<string, string> = {
  USER: 'Benutzer',
  AUDITOR: 'Auditor',
  SYSTEM_ADMIN: 'Systemverwaltung',
}

type Status = 'loading' | 'ready' | 'redeeming' | 'done' | 'invalid' | 'failed'

/**
 * The page behind the handover link (#1563, ADR-0033 Entscheidung 12). It runs in two visits of the
 * same route: first with the code in the URL, where it shows what would move and starts the
 * provider sign-in; then after the provider redirect, where the callback hands it the provider
 * token and it redeems - one call, and only that one, because the account under that identity must
 * not exist a moment earlier.
 *
 * The provider is the one the administration named, read from the preview - the person never picks
 * one, so nobody can end up bound to the wrong identity. There is no way back from a redemption;
 * the page says so before the button.
 */
export default function HandoverPage() {
  usePageTitle('Übergabe')
  // Reads the code and takes it out of the address bar before the first request leaves, so no
  // referrer carries it into an access log (#1540, ADR-0033 Entscheidung 9).
  const token = useLinkToken()
  const navigate = useNavigate()
  const startHandoverSignIn = useAuthStore((s) => s.startHandoverSignIn)
  const completeHandover = useAuthStore((s) => s.completeHandover)
  // Read at the first render rather than in the effect: the first paint already has to say which of
  // the two visits this is, and a state change from inside the effect would cascade a render. The
  // read is pure, so StrictMode's double render changes nothing; the effect below is what drops the
  // pending handover, guarded against the double effect of the same mount.
  const [pending] = useState<PendingHandover | null>(() => peekPendingHandover())
  const [status, setStatus] = useState<Status>(
    pending ? 'redeeming' : token === '' ? 'invalid' : 'loading',
  )
  const [preview, setPreview] = useState<LocalHandoverPreviewResponse | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const started = useRef(false)

  const describe = useCallback((err: unknown): string => {
    if (err instanceof ProviderAccountExistsError) {
      return 'Unter dieser Anbieteridentität besteht bereits ein Konto in OPAA. Zwei Konten werden nie zusammengeführt — wenden Sie sich an Ihre Systemverwaltung.'
    }
    if (err instanceof ProviderMismatchError) {
      return 'Sie haben sich bei einem anderen Anbieter angemeldet als dem, für den die Übergabe angestoßen wurde. Öffnen Sie den Link aus der E-Mail noch einmal.'
    }
    if (err instanceof ProviderSignInInvalidError) {
      return 'Die Anmeldung beim Anbieter konnte nicht geprüft werden. Melden Sie sich erneut an und öffnen Sie den Link aus der E-Mail noch einmal.'
    }
    if (err instanceof HandoverRefusedError) {
      return err.message
    }
    if (err instanceof RateLimitedError) {
      return tooManyRequestsMessage(err.retryAfterSeconds)
    }
    return 'Die Übergabe konnte nicht abgeschlossen werden. Versuchen Sie es später noch einmal.'
  }, [])

  useEffect(() => {
    if (started.current) return
    started.current = true
    if (pending) {
      clearPendingHandover()
      redeemHandover(pending.code, pending.providerToken)
        .then(() => completeHandover(pending.providerToken))
        .then(() => setStatus('done'))
        .catch((err: unknown) => {
          if (err instanceof LinkInvalidError) {
            setStatus('invalid')
            return
          }
          setMessage(describe(err))
          setStatus('failed')
        })
      return
    }
    if (token === '') return
    previewHandover(token).then(
      (result) => {
        setPreview(result)
        setStatus('ready')
      },
      (err: unknown) => {
        if (err instanceof LinkInvalidError) {
          setStatus('invalid')
          return
        }
        setMessage(describe(err))
        setStatus('failed')
      },
    )
  }, [token, pending, describe, completeHandover])

  async function signInAtProvider() {
    if (!preview) return
    setStatus('redeeming')
    const started = await startHandoverSignIn(preview.provider.id, token)
    if (!started) {
      setMessage(
        `Die Anmeldung bei ${preview.provider.displayName} konnte nicht gestartet werden. Versuchen Sie es später noch einmal.`,
      )
      setStatus('failed')
    }
  }

  if (status === 'loading' || status === 'redeeming') {
    return (
      <AuthLayout>
        <PageHeading title={HEADING} variant="h5" />
        <Box
          sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mt: 3, color: 'text.secondary' }}
        >
          <CircularProgress size={20} aria-hidden="true" />
          <Typography role="status" sx={{ fontSize: 13.5 }}>
            {status === 'loading' ? 'Die Übergabe wird geprüft …' : 'Die Übergabe läuft …'}
          </Typography>
        </Box>
      </AuthLayout>
    )
  }

  if (status === 'done') {
    return (
      <AuthLayout>
        <AuthNotice
          heading={HEADING}
          severity="success"
          message="Ihr Zugang gehört jetzt zu Ihrer Anbieteridentität. Melden Sie sich ab sofort über den Anbieter an; Ihr bisheriges Passwort gilt nicht mehr."
          detail="Ihre Spaces, Mitgliedschaften und Ihre Rolle sind unverändert geblieben."
        >
          <Button
            variant="contained"
            onClick={() => navigate(AFTER_SIGN_IN_ROUTE, { replace: true })}
          >
            Weiter zu OPAA
          </Button>
        </AuthNotice>
      </AuthLayout>
    )
  }

  if (status === 'invalid') {
    return (
      <AuthLayout>
        <AuthNotice
          heading={HEADING}
          severity="error"
          message={LINK_INVALID_MESSAGE}
          detail="Der Link gilt 72 Stunden und nur einmal. Ein erneutes Laden dieser Seite hilft nicht — bitten Sie Ihre Systemverwaltung, die Übergabe erneut anzustoßen."
        >
          <Link component={RouterLink} to={LOGIN_ROUTE}>
            Zur Anmeldung
          </Link>
        </AuthNotice>
      </AuthLayout>
    )
  }

  if (status === 'failed' || !preview) {
    return (
      <AuthLayout>
        <AuthNotice
          heading={HEADING}
          severity="error"
          message={message ?? 'Die Übergabe konnte nicht abgeschlossen werden.'}
          detail="Ihr bisheriger Zugang besteht unverändert weiter."
        >
          <Link component={RouterLink} to={LOGIN_ROUTE}>
            Zur Anmeldung
          </Link>
        </AuthNotice>
      </AuthLayout>
    )
  }

  const { provider, scope, reason, displayName } = preview
  return (
    <AuthLayout>
      <PageHeading title={HEADING} variant="h5" sx={{ mb: 2 }} />
      <Typography sx={{ fontSize: 13.5, textAlign: 'left' }}>
        Ihre Systemverwaltung hat die Übergabe Ihres Zugangs „{displayName}“ an{' '}
        <strong>{provider.displayName}</strong> angestoßen. Melden Sie sich dort an, um sie
        abzuschließen.
      </Typography>
      <Alert severity="info" sx={{ mt: 2, textAlign: 'left' }}>
        Anlass der Systemverwaltung: {reason}
      </Alert>

      <Box component="section" aria-labelledby="handover-scope-title" sx={{ mt: 3 }}>
        <SectionEyebrow id="handover-scope-title">Das geht mit</SectionEyebrow>
        <List dense sx={{ textAlign: 'left' }}>
          <ListItem disableGutters>
            <ListItemText
              primary="Persönlicher Space"
              secondary={scope.personalSpaceName ?? 'Noch keiner angelegt'}
            />
          </ListItem>
          <ListItem disableGutters>
            <ListItemText primary="Space-Mitgliedschaften" secondary={scope.spaceMemberships} />
          </ListItem>
          <ListItem disableGutters>
            <ListItemText primary="Gruppenmitgliedschaften" secondary={scope.groupMemberships} />
          </ListItem>
          <ListItem disableGutters>
            <ListItemText
              primary="Rolle"
              secondary={SYSTEM_ROLE_TEXT[scope.systemRole] ?? scope.systemRole}
            />
          </ListItem>
        </List>
      </Box>

      <Alert severity="warning" sx={{ mt: 1, textAlign: 'left' }}>
        Nach der Übergabe melden Sie sich nur noch über {provider.displayName} an. Ihr bisheriges
        Passwort gilt dann nicht mehr, und einen Rückweg gibt es nicht.
      </Alert>

      <Button variant="contained" fullWidth sx={{ mt: 3 }} onClick={() => void signInAtProvider()}>
        Bei {provider.displayName} anmelden und übergeben
      </Button>
      <Typography sx={{ mt: 2, fontSize: 12, color: 'text.secondary' }}>
        Sie können diese Seite auch schließen — Ihr bisheriger Zugang bleibt bis zur Übergabe
        unverändert.
      </Typography>
    </AuthLayout>
  )
}
