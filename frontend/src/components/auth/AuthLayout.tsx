import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import { keyframes } from '@mui/material/styles'
import { navyRoles, radius } from '../../theme/tokens'

// One staged entrance for the card (guidelines 4.5); the theme collapses it under
// prefers-reduced-motion.
const cardReveal = keyframes`
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: none; }
`

/**
 * The frame of every screen that renders before there is a working session - sign-in, system
 * administrators' sign-in, forced password change: navy ground in both colour schemes (mockup 1f),
 * one bordered card that follows the active scheme (guidelines 4.3, borders instead of shadows).
 */
export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <Box
      sx={{
        display: 'grid',
        // minmax(0, 1fr): the track may shrink below the card's preferred width, so a narrow
        // viewport narrows the card instead of scrolling sideways
        gridTemplateColumns: 'minmax(0, 1fr)',
        placeItems: 'center',
        minHeight: '100vh',
        p: { xs: 2, sm: 3 },
        bgcolor: navyRoles.bg1,
      }}
    >
      <Box
        sx={{
          width: '100%',
          maxWidth: 440,
          bgcolor: 'background.paper',
          border: 2,
          borderColor: 'primary.main',
          borderRadius: `${radius.xl}px`,
          px: { xs: 3, sm: 4.25 },
          py: 4.5,
          animation: `${cardReveal} 240ms ease-out both`,
        }}
      >
        {children}
      </Box>
    </Box>
  )
}
