import { useRef } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import ExploreOutlinedIcon from '@mui/icons-material/ExploreOutlined'
import { focusChatInput } from '@/shared/ui/a11y/tourAnchor'
import { answerQuickTourHint, useQuickTourHintVisible, useTourAudience } from './quickTourActions'
import { useQuickTourStore } from './quickTourStore'
import { tourStepsFor } from './tourSteps'

const TITLE_ID = 'quick-tour-hint-title'
const TEXT_ID = 'quick-tour-hint-text'

/**
 * The invitation of an empty chat, as a modal dialog over a blurred page: nothing else opens on
 * its own. Only an answer closes it - "Rundgang starten", "Nicht jetzt" or Escape (same as "Nicht
 * jetzt"); a click beside it does not. Every answer sets the marker for good.
 */
export default function QuickTourHint() {
  const visible = useQuickTourHintVisible()
  const audience = useTourAudience()
  const start = useQuickTourStore((s) => s.start)
  const startRef = useRef<HTMLButtonElement>(null)
  if (!visible) return null

  const count = tourStepsFor(audience).length
  const notNow = () => {
    answerQuickTourHint()
    // The dialog unmounts with its buttons; the focus goes where the person starts.
    window.setTimeout(focusChatInput, 0)
  }

  return (
    <Dialog
      open
      onClose={(_, reason) => {
        if (reason === 'escapeKeyDown') notNow()
      }}
      disableRestoreFocus
      aria-labelledby={TITLE_ID}
      aria-describedby={TEXT_ID}
      slotProps={{
        paper: { sx: { width: '100%', maxWidth: 440, m: 2 } },
        // The answer most people want has the focus from the start.
        transition: { onEntering: () => startRef.current?.focus() },
      }}
    >
      <DialogContent sx={{ pt: 3.5, px: { xs: 3, sm: 4 }, pb: 1 }}>
        <Box
          aria-hidden
          sx={(theme) => ({
            width: 48,
            height: 48,
            borderRadius: '50%',
            display: 'grid',
            placeItems: 'center',
            mb: 2,
            color: 'primary.main',
            bgcolor: alpha(theme.palette.primary.main, 0.12),
          })}
        >
          <ExploreOutlinedIcon />
        </Box>
        <Typography
          id={TITLE_ID}
          variant="h5"
          component="h2"
          sx={{ fontWeight: 600, mb: 1, color: 'text.primary' }}
        >
          Neu hier?
        </Typography>
        <Typography id={TEXT_ID} variant="body1" sx={{ color: 'text.secondary' }}>
          Ein Rundgang in {count} Schritten zeigt, wie Sie fragen, worauf sich Antworten stützen und
          wo Sie Wissen finden.
        </Typography>
        <Box aria-hidden sx={{ display: 'flex', gap: 0.75, mt: 2.5 }}>
          {Array.from({ length: count }, (_, i) => (
            <Box
              key={i}
              sx={(theme) => ({
                width: i === 0 ? 20 : 8,
                height: 8,
                borderRadius: 4,
                bgcolor: i === 0 ? 'primary.main' : alpha(theme.palette.primary.main, 0.35),
              })}
            />
          ))}
        </Box>
      </DialogContent>
      <DialogActions
        sx={{
          px: { xs: 3, sm: 4 },
          pt: 2,
          pb: 3,
          gap: 1,
          // Narrow screens stack the answers, the start on top, both over the full width.
          flexDirection: { xs: 'column-reverse', sm: 'row' },
          alignItems: 'stretch',
          '& > :not(style) ~ :not(style)': { ml: 0 },
          '& .MuiButton-root': { whiteSpace: 'nowrap' },
        }}
      >
        <Button variant="text" onClick={notNow}>
          Nicht jetzt
        </Button>
        <Button
          variant="contained"
          ref={startRef}
          disableFocusRipple
          onClick={() => {
            answerQuickTourHint()
            start()
          }}
        >
          Rundgang starten
        </Button>
      </DialogActions>
    </Dialog>
  )
}
