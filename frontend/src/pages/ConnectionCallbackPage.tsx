import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import type {
  ConnectionAuthorizationCompleteRequest,
  ConnectionAuthorizationCompleteResponse,
} from '../types/api'
import {
  completeConnectionAuthorization,
  ConnectAccountError,
} from '../services/connectedAccountApi'
import { useAuthStore } from '../stores/authStore'
import { notify } from '../stores/notificationStore'
import PageHeading from '../components/a11y/PageHeading'
import { CONNECTED_ACCOUNTS_ROUTE, CONNECTION_CALLBACK_ROUTE, LOGIN_ROUTE } from '../routes'

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

/** Only a path of this application; anything else goes to the connected accounts. */
function safeReturn(returnTo: string): string {
  return returnTo.startsWith('/') && !returnTo.startsWith('//')
    ? returnTo
    : CONNECTED_ACCOUNTS_ROUTE
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

type Outcome = { kind: 'pending' } | { kind: 'failed'; message: string; signIn?: boolean }

/**
 * Where the provider returns after an OAuth consent (ADR-0025): the page hands state and code, or
 * the provider's error, once to the server with the session of this tab and goes on to the page
 * the server names. The address is cleaned at once, so neither code nor state stays in the
 * history. It sits outside the protected routes, so a missing session never carries them into a
 * sign-in redirect.
 */
export default function ConnectionCallbackPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const isLoading = useAuthStore((s) => s.isLoading)
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const [request] = useState(() => readCallback(location.search))
  const [result, setResult] = useState<Outcome>({ kind: 'pending' })

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
        notify(
          completed.account
            ? `Ihr Konto ist mit „${completed.account.profileName}“ verbunden.`
            : 'Die Verbindung ist hergestellt.',
          'success',
        )
        navigate(safeReturn(completed.returnTo), { replace: true })
      })
      .catch((err: unknown) => {
        if (active) setResult({ kind: 'failed', message: failureMessage(err) })
      })
    return () => {
      active = false
    }
  }, [isLoading, isAuthenticated, request, navigate])

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
      <PageHeading title="Konto verbinden" />
      {outcome.kind === 'pending' ? (
        <>
          <CircularProgress aria-hidden size={28} />
          <Typography role="status">Die Verbindung wird abgeschlossen …</Typography>
        </>
      ) : (
        <>
          <Alert severity="error" sx={{ maxWidth: 560 }}>
            {outcome.message}
          </Alert>
          <Button
            variant="outlined"
            onClick={() =>
              navigate(outcome.signIn ? LOGIN_ROUTE : CONNECTED_ACCOUNTS_ROUTE, {
                replace: true,
                state: outcome.signIn ? { from: CONNECTED_ACCOUNTS_ROUTE } : undefined,
              })
            }
          >
            {outcome.signIn ? 'Zur Anmeldung' : 'Zu den verbundenen Konten'}
          </Button>
        </>
      )}
    </Box>
  )
}
