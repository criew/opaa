import { useEffect } from 'react'
import { Navigate, useLocation } from 'react-router'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import { useAuthStore } from '../stores/authStore'
import { LOGIN_ROUTE, PASSWORD_ROUTE } from '../routes'

/**
 * What stands between the start of an OIDC sign-out and the browser leaving for the provider -
 * neither the application nor the login page, so nothing can be clicked in that gap.
 */
function SignOutPending() {
  // Coming "back" from the provider may restore this very page from the back-forward cache; it
  // would wait here forever, so it starts over and shows the real state instead.
  useEffect(() => {
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) window.location.reload()
    }
    window.addEventListener('pageshow', onPageShow)
    return () => window.removeEventListener('pageshow', onPageShow)
  }, [])

  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
        alignItems: 'center',
        gap: 2,
        height: '100vh',
      }}
    >
      <CircularProgress aria-hidden size={28} />
      <Typography role="status" sx={{ color: 'text.secondary' }}>
        Sie werden abgemeldet …
      </Typography>
    </Box>
  )
}

export default function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const isLoading = useAuthStore((s) => s.isLoading)
  const passwordChangeRequired = useAuthStore((s) => s.passwordChangeRequired)
  const signedOut = useAuthStore((s) => s.signedOut)
  const isSigningOut = useAuthStore((s) => s.isSigningOut)
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

  if (isSigningOut) {
    return <SignOutPending />
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
