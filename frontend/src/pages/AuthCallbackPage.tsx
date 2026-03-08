import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import { useAuthStore } from '../stores/authStore'

export default function AuthCallbackPage() {
  const handleOidcCallback = useAuthStore((s) => s.handleOidcCallback)
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const isLoading = useAuthStore((s) => s.isLoading)
  const userManager = useAuthStore((s) => s.userManager)
  const error = useAuthStore((s) => s.error)
  const navigate = useNavigate()

  useEffect(() => {
    if (!isLoading && userManager) {
      handleOidcCallback()
    }
  }, [isLoading, userManager, handleOidcCallback])

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
      }}
    >
      {error ? (
        <Typography color="error">{error}</Typography>
      ) : (
        <>
          <CircularProgress />
          <Typography sx={{ mt: 2 }}>Completing sign in...</Typography>
        </>
      )}
    </Box>
  )
}
