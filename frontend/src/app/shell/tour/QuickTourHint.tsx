import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import { radius } from '@/shared/theme/tokens'
import { focusChatInput } from '@/shared/ui/a11y/tourAnchor'
import { answerQuickTourHint, useQuickTourHintVisible, useTourAudience } from './quickTourActions'
import { useQuickTourStore } from './quickTourStore'
import { tourStepsFor } from './tourSteps'

const TITLE_ID = 'quick-tour-hint-title'

/**
 * The invitation below the greeting of an empty chat: nothing opens on its own. Both buttons
 * answer the hint for good; the profile menu starts the tour again at any time.
 */
export default function QuickTourHint() {
  const visible = useQuickTourHintVisible()
  const audience = useTourAudience()
  const start = useQuickTourStore((s) => s.start)
  if (!visible) return null

  const count = tourStepsFor(audience).length

  return (
    <Box
      component="section"
      aria-labelledby={TITLE_ID}
      sx={{
        width: '100%',
        maxWidth: 520,
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
        id={TITLE_ID}
        variant="subtitle1"
        component="h2"
        sx={{ fontWeight: 600, mb: 0.5 }}
      >
        Neu hier?
      </Typography>
      <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1.5 }}>
        Ein Rundgang in {count} Schritten zeigt, wie Sie fragen, worauf sich Antworten stützen und
        wo Sie Wissen finden.
      </Typography>
      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        <Button
          variant="outlined"
          onClick={() => {
            answerQuickTourHint()
            start()
          }}
        >
          Rundgang starten
        </Button>
        <Button
          variant="text"
          onClick={() => {
            answerQuickTourHint()
            // The hint unmounts with its buttons; the focus goes where the person starts.
            window.setTimeout(focusChatInput, 0)
          }}
        >
          Nicht jetzt
        </Button>
      </Box>
    </Box>
  )
}
