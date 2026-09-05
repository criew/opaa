import { useEffect } from 'react'
import { useNavigate } from 'react-router'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import { useAuthStore } from '../stores/authStore'
import { usePageTitle } from '../hooks/usePageTitle'

export default function AuthCallbackPage() {
  usePageTitle('Anmeldung')
  const handleOidcCallback = useAuthStore((s) => s.handleOidcCallback)
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const isLoading = useAuthStore((s) => s.isLoading)
  const mode = useAuthStore((s) => s.mode)
  const error = useAuthStore((s) => s.error)
  const navigate = useNavigate()

  // initialize() already activated the manager of the provider this tab started the flow at
  // (ADR-0025); handleOidcCallback reports it when that provider is gone in the meantime.
  useEffect(() => {
    if (!isLoading && mode === 'oidc') {
      void handleOidcCallback()
    }
  }, [isLoading, mode, handleOidcCallback])

  useEffect(() => {
    if (isAuthenticated) {
      navigate('/chat', { replace: true })
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
          <Button variant="outlined" onClick={() => navigate('/login', { replace: true })}>
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
