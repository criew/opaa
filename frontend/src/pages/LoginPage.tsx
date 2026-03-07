import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Paper from '@mui/material/Paper'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useAuthStore } from '../stores/authStore'

export default function LoginPage() {
  const mode = useAuthStore((s) => s.mode)
  const error = useAuthStore((s) => s.error)
  const isLoading = useAuthStore((s) => s.isLoading)
  const loginBasic = useAuthStore((s) => s.loginBasic)
  const loginOidc = useAuthStore((s) => s.loginOidc)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')

  async function handleBasicLogin(e: React.FormEvent) {
    e.preventDefault()
    await loginBasic(username, password)
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
        <Typography variant="h5" fontWeight={700} gutterBottom>
          OPAA
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
          Sign in to continue
        </Typography>

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {mode === 'basic' && (
          <form onSubmit={handleBasicLogin}>
            <TextField
              label="Username"
              fullWidth
              margin="normal"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
            />
            <TextField
              label="Password"
              type="password"
              fullWidth
              margin="normal"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
            />
            <Button type="submit" variant="contained" fullWidth disabled={isLoading} sx={{ mt: 2 }}>
              Sign In
            </Button>
          </form>
        )}

        {mode === 'oidc' && (
          <Button variant="contained" fullWidth onClick={loginOidc} disabled={isLoading}>
            Sign in with SSO
          </Button>
        )}
      </Paper>
    </Box>
  )
}
