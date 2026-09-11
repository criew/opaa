import { Navigate, useLocation } from 'react-router'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import { useAuthStore } from '../stores/authStore'
import { PASSWORD_ROUTE } from '../routes'

export default function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const isLoading = useAuthStore((s) => s.isLoading)
  const passwordChangeRequired = useAuthStore((s) => s.passwordChangeRequired)
  const location = useLocation()

  if (isLoading) {
    return (
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          height: '100vh',
        }}
      >
        <CircularProgress />
      </Box>
    )
  }

  if (!isAuthenticated) {
    return (
      <Navigate
        to="/login"
        replace
        state={{ from: `${location.pathname}${location.search}${location.hash}` }}
      />
    )
  }

  // ADR-0033, Entscheidung 8: while the account owes a new password, the backend answers every
  // route but /api/v1/auth/local/* with 403 - showing the application shell would be showing a
  // frame around nothing but errors.
  if (passwordChangeRequired) {
    return <Navigate to={PASSWORD_ROUTE} replace />
  }

  return <>{children}</>
}
