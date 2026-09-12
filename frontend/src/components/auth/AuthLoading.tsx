import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import PageHeading from '../a11y/PageHeading'

/**
 * What a self-service page shows while the installation's configuration is still on its way
 * (guidelines 5.7). Deliberately not an empty render: the page already has its heading and its
 * document title, and a visitor who waits is told that something is happening rather than looking
 * at a blank card.
 */
export default function AuthLoading({ heading }: { heading: string }) {
  return (
    <>
      <PageHeading title={heading} variant="h6" />
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mt: 3, color: 'text.secondary' }}>
        <CircularProgress size={20} aria-hidden="true" />
        <Typography role="status" sx={{ fontSize: 13.5 }}>
          Wird geladen …
        </Typography>
      </Box>
    </>
  )
}
