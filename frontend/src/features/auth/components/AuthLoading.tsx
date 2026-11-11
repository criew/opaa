import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import { fontSize } from '@/shared/theme/tokens'

interface AuthLoadingProps {
  heading: string
  /** Names what is being waited for where „Wird geladen …“ would say too little. */
  message?: string
}

/**
 * What a page before the session shows while it waits - by default for the installation's
 * configuration (guidelines 5.7). Deliberately not an empty render: the page already has its
 * heading and its document title, and a visitor who waits is told that something is happening
 * rather than looking at a blank card.
 */
export default function AuthLoading({ heading, message = 'Wird geladen …' }: AuthLoadingProps) {
  return (
    <>
      <PageHeading title={heading} variant="h5" />
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mt: 3, color: 'text.secondary' }}>
        <CircularProgress size={20} aria-hidden="true" />
        <Typography role="status" sx={{ fontSize: fontSize.text }}>
          {message}
        </Typography>
      </Box>
    </>
  )
}
