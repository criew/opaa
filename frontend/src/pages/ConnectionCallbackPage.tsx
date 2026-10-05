import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type {
  ConnectionAuthorizationCompleteRequest,
  ConnectionAuthorizationCompleteResponse,
} from '../types/api'
import {
  completeConnectionAuthorization,
  ConnectAccountError,
  startSourceAuthorization,
} from '../services/connectedAccountApi'
import { leaveFor } from '../services/leaveApp'
import { useAuthStore } from '../stores/authStore'
import { confirmAction } from '../stores/confirmStore'
import { notify } from '../stores/notificationStore'
import PageHeading from '../components/a11y/PageHeading'
import {
  attachPendingConnection,
  forgetConsentIntent,
  readConsentIntent,
  type SourceConsentIntent,
} from '../components/library/sourceConsent'
import { safeRedirectPath } from '../utils/safeRedirectPath'
import { CONNECTED_ACCOUNTS_ROUTE, CONNECTION_CALLBACK_ROUTE, LOGIN_ROUTE } from '../routes'

const NEW_LIBRARY_ROUTE = '/libraries/new'
const ACCOUNT_CHANGED = 'ACCOUNT_CHANGED'

/**
 * Completions under way, by state: a state is redeemed once per page load, also when React runs
 * the effect twice. A reload finds the address already cleaned and redeems nothing.
 */
const completions = new Map<string, Promise<ConnectionAuthorizationCompleteResponse>>()

function completeOnce(
  request: ConnectionAuthorizationCompleteRequest,
): Promise<ConnectionAuthorizationCompleteResponse> {
  const running = completions.get(request.state)
  if (running) return running
  const started = completeConnectionAuthorization(request)
  completions.set(request.state, started)
  return started
}

/** What the provider sent back, read from the address once; `null` without a state. */
function readCallback(search: string): ConnectionAuthorizationCompleteRequest | null {
  const params = new URLSearchParams(search)
  const state = params.get('state')
  if (!state) return null
  return { state, code: params.get('code'), error: params.get('error') }
}

function failureMessage(err: unknown): string {
  const status = err instanceof ConnectAccountError ? err.status : null
  const message = err instanceof Error && err.message ? err.message : null
  switch (status) {
    case 404:
      return (
        message ??
        'Diese Anmeldung beim Anbieter ist unbekannt, abgelaufen oder schon abgeschlossen. Bitte verbinden Sie erneut.'
      )
    case 409:
      return (
        message ??
        'Der Zugang wurde geändert, während Sie beim Anbieter waren. Bitte verbinden Sie erneut.'
      )
    default:
      return (
        message ?? 'Die Verbindung konnte nicht abgeschlossen werden. Es wurde nichts gespeichert.'
      )
  }
}

function libraryRoute(libraryId: string): string {
  return `/libraries/${libraryId}?tab=quelle`
}

/** Where the person goes on after the consent, and the message saying so. */
function afterCompletion(completed: ConnectionAuthorizationCompleteResponse): {
  target: string
  message: string
} {
  switch (completed.purpose) {
    case 'LIBRARY_NEW': {
      const pending = completed.pendingConnection
      if (pending) attachPendingConnection(completed.profileId, pending)
      return {
        target: safeRedirectPath(completed.returnTo, NEW_LIBRARY_ROUTE),
        message: pending?.accountLabel
          ? `Die Quelle ist verbunden als „${pending.accountLabel}“.`
          : 'Die Quelle ist verbunden.',
      }
    }
    case 'LIBRARY_RECONNECT': {
      forgetConsentIntent()
      const fallback = completed.libraryId
        ? libraryRoute(completed.libraryId)
        : CONNECTED_ACCOUNTS_ROUTE
      const target = safeRedirectPath(completed.returnTo, fallback)
      return {
        target: target.includes('?') ? target : `${target}?tab=quelle`,
        message: 'Die Quelle der Bibliothek ist neu verbunden.',
      }
    }
    case 'ACCOUNT':
    default:
      return {
        target: safeRedirectPath(completed.returnTo, CONNECTED_ACCOUNTS_ROUTE),
        message: completed.account
          ? `Ihr Konto ist mit „${completed.account.profileName}“ verbunden.`
          : 'Die Verbindung ist hergestellt.',
      }
  }
}

/** The way back from a failure: to the wizard or library that started the consent, if any. */
function wayBack(intent: SourceConsentIntent | null): { label: string; target: string } {
  if (intent?.purpose === 'LIBRARY_NEW') {
    return { label: 'Zurück zum Assistenten', target: NEW_LIBRARY_ROUTE }
  }
  if (intent?.purpose === 'LIBRARY_RECONNECT') {
    return { label: 'Zurück zur Bibliothek', target: libraryRoute(intent.libraryId) }
  }
  return { label: 'Zu den verbundenen Konten', target: CONNECTED_ACCOUNTS_ROUTE }
}

type Outcome =
  | { kind: 'pending' }
  | { kind: 'failed'; message: string; signIn?: boolean; accountChanged?: boolean }

/**
 * Where the provider returns after an OAuth consent (ADR-0025): the page hands state and code, or
 * the provider's error, once to the server with the session of this tab and goes on to the page
 * the server names. The address is cleaned at once, so neither code nor state stays in the
 * history. It sits outside the protected routes, so a missing session never carries them into a
 * sign-in redirect. A consent for a library's source returns to the wizard or library that
 * started it, as remembered in this tab before leaving.
 */
export default function ConnectionCallbackPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const isLoading = useAuthStore((s) => s.isLoading)
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const [request] = useState(() => readCallback(location.search))
  const [intent] = useState(readConsentIntent)
  const [result, setResult] = useState<Outcome>({ kind: 'pending' })
  const [restarting, setRestarting] = useState(false)

  // what fails before anything is sent follows from the page itself, not from an answer
  const outcome: Outcome = isLoading
    ? { kind: 'pending' }
    : request === null
      ? {
          kind: 'failed',
          message:
            'Hier gibt es nichts abzuschließen: Die Rückmeldung des Anbieters fehlt oder wurde schon verarbeitet. Den Stand Ihrer Verbindungen sehen Sie unter „Verbundene Konten“.',
        }
      : !isAuthenticated
        ? {
            kind: 'failed',
            signIn: true,
            message:
              'Ihre Sitzung ist abgelaufen, deshalb konnte die Verbindung nicht abgeschlossen werden. Bitte melden Sie sich an und verbinden Sie das Konto erneut.',
          }
        : result

  useEffect(() => {
    if (location.search || location.hash) {
      navigate(CONNECTION_CALLBACK_ROUTE, { replace: true })
    }
  }, [location.search, location.hash, navigate])

  useEffect(() => {
    if (isLoading || request === null || !isAuthenticated) return
    let active = true
    completeOnce(request)
      .then((completed) => {
        if (!active) return
        const { target, message } = afterCompletion(completed)
        notify(message, 'success')
        navigate(target, { replace: true })
      })
      .catch((err: unknown) => {
        if (!active) return
        setResult({
          kind: 'failed',
          message: failureMessage(err),
          accountChanged: err instanceof ConnectAccountError && err.code === ACCOUNT_CHANGED,
        })
      })
    return () => {
      active = false
    }
  }, [isLoading, isAuthenticated, request, navigate])

  /** Starts the consent again, now accepting the other account and the loss of the sync state. */
  async function handleAccountChange() {
    if (intent?.purpose !== 'LIBRARY_RECONNECT' || restarting) return
    const confirmed = await confirmAction({
      question: 'Quelle mit dem anderen Konto verbinden?',
      consequence:
        'Der Abgleichstand der Bibliothek wird verworfen; der nächste Lauf liest die Quelle vollständig neu ein. Sie werden dafür noch einmal zum Anbieter weitergeleitet.',
      confirmLabel: 'Mit diesem Konto verbinden',
      tone: 'caution',
    })
    if (!confirmed) return
    setRestarting(true)
    try {
      const started = await startSourceAuthorization({
        profileId: intent.profileId,
        purpose: 'LIBRARY_RECONNECT',
        libraryId: intent.libraryId,
        serviceAccountConfirmed: true,
        confirmAccountChange: true,
      })
      leaveFor(started.authorizationUrl)
    } catch (err) {
      notify(
        err instanceof Error && err.message
          ? err.message
          : 'Die Anmeldung beim Anbieter ließ sich nicht starten.',
        'error',
      )
      setRestarting(false)
    }
  }

  const back = wayBack(intent)
  const offersAccountChange =
    outcome.kind === 'failed' &&
    Boolean(outcome.accountChanged) &&
    intent?.purpose === 'LIBRARY_RECONNECT'

  function handleBack() {
    if (outcome.kind === 'failed' && outcome.signIn) {
      navigate(LOGIN_ROUTE, { replace: true, state: { from: CONNECTED_ACCOUNTS_ROUTE } })
      return
    }
    // the wizard reads its draft back; a library has nothing left to return to
    if (intent?.purpose === 'LIBRARY_RECONNECT') forgetConsentIntent()
    navigate(back.target, { replace: true })
  }

  return (
    <Box
      component="main"
      sx={{
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
        alignItems: 'center',
        minHeight: '100vh',
        gap: 2,
        p: 3,
      }}
    >
      <PageHeading title={intent ? 'Quelle verbinden' : 'Konto verbinden'} />
      {outcome.kind === 'pending' ? (
        <>
          <CircularProgress aria-hidden size={28} />
          <Typography role="status">Die Verbindung wird abgeschlossen …</Typography>
        </>
      ) : (
        <>
          <Alert severity={offersAccountChange ? 'warning' : 'error'} sx={{ maxWidth: 560 }}>
            {outcome.message}
          </Alert>
          <Stack direction="row" spacing={1}>
            {offersAccountChange && (
              <Button
                variant="contained"
                disabled={restarting}
                onClick={() => void handleAccountChange()}
              >
                Mit diesem Konto verbinden
              </Button>
            )}
            <Button variant="outlined" onClick={handleBack}>
              {outcome.signIn ? 'Zur Anmeldung' : back.label}
            </Button>
          </Stack>
        </>
      )}
    </Box>
  )
}
