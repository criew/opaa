import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Paper from '@mui/material/Paper'
import Typography from '@mui/material/Typography'
import { Navigate, useLocation } from 'react-router'
import { useAuthStore } from '../stores/authStore'

export default function LoginPage() {
  const location = useLocation()
  const mode = useAuthStore((s) => s.mode)
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const error = useAuthStore((s) => s.error)
  const isLoading = useAuthStore((s) => s.isLoading)
  const loginOidc = useAuthStore((s) => s.loginOidc)

  if (isAuthenticated) {
    const from =
      typeof location.state === 'object' &&
      location.state !== null &&
      'from' in location.state &&
      typeof location.state.from === 'string'
        ? location.state.from
        : '/chat'
    return <Navigate to={from} replace />
  }

  return (
    <Box
      sx={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        height: '100vh',
        bgcolor: 'background.default',
      }}
    >
      <Paper sx={{ p: 4, maxWidth: 400, width: '100%' }}>
        <Typography variant="h5" gutterBottom sx={{ fontWeight: 700 }}>
          OPAA
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
          Zum Fortfahren anmelden
        </Typography>

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {mode === 'oidc' && (
          <Button variant="contained" fullWidth onClick={loginOidc} disabled={isLoading}>
            Mit SSO anmelden
          </Button>
        )}
      </Paper>
    </Box>
  )
}
