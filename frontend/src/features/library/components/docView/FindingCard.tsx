import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import CenterFocusStrongIcon from '@mui/icons-material/CenterFocusStrong'
import { fontSize } from '@/shared/theme/tokens'
import { BRAND } from './docViewVariant'

/** C: what the answer relied on, beside the original - place, quote, status and the jump to it. */
export default function FindingCard({
  place,
  quote,
  status,
  warning,
  onJump,
}: {
  place: string | null
  quote: string
  status: string
  warning: boolean
  onJump?: () => void
}) {
  return (
    <Box
      component="aside"
      aria-label="Fundstelle"
      sx={{ width: { sm: 300, lg: 340 }, flex: 'none', p: { xs: 1.5, sm: 2 }, pt: { sm: 3 } }}
    >
      <Box
        sx={{
          bgcolor: 'background.paper',
          borderRadius: 2,
          p: 2,
          display: 'flex',
          flexDirection: 'column',
          gap: 1.25,
          borderTop: `4px solid ${BRAND.light}`,
        }}
      >
        <Typography
          component="h3"
          sx={{
            fontSize: fontSize.meta,
            fontWeight: 700,
            letterSpacing: '0.06em',
            textTransform: 'uppercase',
            color: 'primary.main',
          }}
        >
          Fundstelle
        </Typography>
        {place && (
          <Typography sx={{ fontSize: fontSize.text, fontWeight: 600 }}>{place}</Typography>
        )}
        {quote && (
          <Typography
            component="blockquote"
            sx={{
              display: { xs: 'none', sm: 'block' },
              m: 0,
              pl: 1.5,
              borderLeft: `3px solid ${BRAND.blue}`,
              fontSize: fontSize.text,
              color: 'text.secondary',
              fontStyle: 'italic',
            }}
          >
            „{quote}“
          </Typography>
        )}
        {status && (
          <Typography
            role="status"
            sx={{
              fontSize: fontSize.meta,
              color: warning ? 'warning.main' : 'text.secondary',
              fontWeight: warning ? 600 : 400,
            }}
          >
            {status}
          </Typography>
        )}
        {onJump && (
          <Button
            size="small"
            variant="contained"
            startIcon={<CenterFocusStrongIcon fontSize="small" />}
            onClick={onJump}
            sx={{ alignSelf: 'flex-start' }}
          >
            Zur Fundstelle
          </Button>
        )}
      </Box>
    </Box>
  )
}
