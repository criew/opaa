import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import { keyframes } from '@mui/material/styles'

const fadeIn = keyframes`
  from { opacity: 0; }
  to { opacity: 1; }
`

/**
 * Suspense fallback while a page's chunk loads. Fades in only after a short delay, so a chunk
 * from the cache does not flash a spinner.
 */
export default function PageLoading() {
  return (
    <Box
      sx={{
        flexGrow: 1,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: 160,
        opacity: 0,
        animation: `${fadeIn} 200ms ease-in 300ms forwards`,
        '@media (prefers-reduced-motion: reduce)': { animation: 'none', opacity: 1 },
      }}
    >
      <CircularProgress size={28} aria-label="Seite wird geladen" />
    </Box>
  )
}
