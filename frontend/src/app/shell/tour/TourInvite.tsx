import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import { useAuthStore } from '@/shared/session/authStore'
import { radius } from '@/shared/theme/tokens'
import { focusChatInput, stepsFor } from './tourSteps'
import { useTourStore } from './tourStore'

/**
 * Variant D: the empty chat keeps its greeting and offers the coachmark tour below it - nothing
 * opens on its own. "Nicht jetzt" counts as seen; the profile menu still starts the tour.
 */
export default function TourInvite() {
  const isAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const { start, finish } = useTourStore()
  const isDesktop = useMediaQuery(useTheme().breakpoints.up('md'))
  const count = stepsFor(isAdmin, isDesktop).length

  return (
    <Box
      component="section"
      aria-labelledby="tour-invite-title"
      sx={{
        width: '100%',
        maxWidth: 520,
        mx: 2,
        border: 1,
        borderColor: 'divider',
        borderRadius: `${radius.md}px`,
        bgcolor: 'background.paper',
        px: 2.5,
        py: 2,
        color: 'text.primary',
      }}
    >
      <Typography
        id="tour-invite-title"
        component="h2"
        sx={{ fontSize: 15, fontWeight: 600, mb: 0.5 }}
      >
        Neu hier?
      </Typography>
      <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1.5 }}>
        Ein Rundgang in {count} Schritten zeigt, wie Sie fragen, worauf sich Antworten stützen und
        wo Sie Wissen finden.
      </Typography>
      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        <Button variant="outlined" onClick={start}>
          Rundgang starten
        </Button>
        <Button
          variant="text"
          onClick={() => {
            // The invitation unmounts; the focus goes where the person starts, not to <body>.
            finish()
            window.setTimeout(focusChatInput, 0)
          }}
        >
          Nicht jetzt
        </Button>
      </Box>
    </Box>
  )
}
