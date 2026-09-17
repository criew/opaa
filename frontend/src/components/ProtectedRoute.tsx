import { Navigate, useLocation } from 'react-router'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import { useAuthStore } from '../stores/authStore'
import { LOGIN_ROUTE, PASSWORD_ROUTE } from '../routes'

export default function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const isLoading = useAuthStore((s) => s.isLoading)
  const passwordChangeRequired = useAuthStore((s) => s.passwordChangeRequired)
  const signedOut = useAuthStore((s) => s.signedOut)
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

  const from = `${location.pathname}${location.search}${location.hash}`

  if (!isAuthenticated) {
    // After a deliberate sign-out the page left behind is no return target - see signedOut.
    return <Navigate to={LOGIN_ROUTE} replace state={signedOut ? undefined : { from }} />
  }

  // ADR-0033, Entscheidung 8: while the account owes a new password, the backend answers every
  // route but /api/v1/auth/local/* with 403 - showing the application shell would be showing a
  // frame around nothing but errors.
  if (passwordChangeRequired) {
    // The denied route travels along, so the change continues where it interrupted.
    return <Navigate to={PASSWORD_ROUTE} replace state={{ from }} />
  }

  return <>{children}</>
}
