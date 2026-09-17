import { useEffect } from 'react'
import { useNavigate } from 'react-router'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import { useAuthStore } from '../stores/authStore'
import { isHandoverInFlight } from '../stores/handoverFlow'
import { usePageTitle } from '../hooks/usePageTitle'
import { AFTER_SIGN_IN_ROUTE, HANDOVER_ROUTE, LOGIN_ROUTE } from '../routes'

export default function AuthCallbackPage() {
  usePageTitle('Anmeldung')
  const handleOidcCallback = useAuthStore((s) => s.handleOidcCallback)
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const isLoading = useAuthStore((s) => s.isLoading)
  const mode = useAuthStore((s) => s.mode)
  const error = useAuthStore((s) => s.error)
  const navigate = useNavigate()

  // initialize() already activated the manager of the provider this tab started the flow at
  // (ADR-0025); handleOidcCallback reports it when that provider is gone in the meantime. A
  // callback that carries a handover (#1563) goes back to the page that started it, which holds
  // the provider token for the one redemption call - there is no session yet, on purpose.
  useEffect(() => {
    if (!isLoading && mode === 'oidc') {
      void handleOidcCallback().then((outcome) => {
        if (outcome === 'handover') {
          navigate(HANDOVER_ROUTE, { replace: true })
        }
        // #1631: the provider turned the automatic attempt down - what follows is the sign-in page
        // as it would have stood without it. `replace` drops this callback from the history, so
        // "back" cannot run into the redirect a second time.
        if (outcome === 'silent-refused') {
          navigate(LOGIN_ROUTE, { replace: true })
        }
      })
    }
  }, [isLoading, mode, handleOidcCallback, navigate])

  useEffect(() => {
    // A handover owns this callback until its page has run (#1563): a session this tab happens to
    // hold - a local one restored at start-up, an OIDC one from before - must not carry the person
    // into the application and leave the redemption unfinished.
    if (isAuthenticated && !isHandoverInFlight()) {
      navigate(AFTER_SIGN_IN_ROUTE, { replace: true })
    }
  }, [isAuthenticated, navigate])

  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
        alignItems: 'center',
        height: '100vh',
        gap: 2,
      }}
    >
      {error ? (
        <>
          <Typography color="error">{error}</Typography>
          <Button variant="outlined" onClick={() => navigate(LOGIN_ROUTE, { replace: true })}>
            Zur Anmeldung
          </Button>
        </>
      ) : (
        <>
          <CircularProgress />
          <Typography>Anmeldung wird abgeschlossen …</Typography>
        </>
      )}
    </Box>
  )
}
