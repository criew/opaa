import { useRef } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Backdrop from '@mui/material/Backdrop'
import Fade from '@mui/material/Fade'
import Modal from '@mui/material/Modal'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { alpha } from '@mui/material/styles'
import ExploreOutlinedIcon from '@mui/icons-material/ExploreOutlined'
import { radius, shadow } from '@/shared/theme/tokens'
import { focusChatInput } from '@/shared/ui/a11y/tourAnchor'
import { answerQuickTourHint, useQuickTourHintVisible, useTourAudience } from './quickTourActions'
import { useQuickTourStore } from './quickTourStore'
import { tourStepsFor } from './tourSteps'

const TITLE_ID = 'quick-tour-hint-title'
const TEXT_ID = 'quick-tour-hint-text'

/**
 * The invitation of an empty chat, a modal dialog over a blurred page; nothing else opens on its
 * own. Only an answer closes it - "Rundgang starten", "Nicht jetzt" or Escape (like "Nicht jetzt"),
 * never a click beside it - and every answer sets the marker for good. Built on Modal like the
 * tour, because the dialogs of shared/ui/dialog close on a click beside them.
 */
export default function QuickTourHint() {
  const visible = useQuickTourHintVisible()
  const audience = useTourAudience(visible)
  const start = useQuickTourStore((s) => s.start)
  const startRef = useRef<HTMLButtonElement>(null)
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)')
  if (!visible) return null

  const count = tourStepsFor(audience).length
  const notNow = () => {
    answerQuickTourHint()
    // The dialog unmounts with its buttons; the focus goes where the person starts.
    window.setTimeout(focusChatInput, 0)
  }

  return (
    <Modal
      open
      onClose={(_, reason) => {
        if (reason === 'escapeKeyDown') notNow()
      }}
      disableRestoreFocus
      // The themed backdrop of level 3: scrim and 2 px blur, like every dialog (guidelines 4.3).
      slots={{ backdrop: Backdrop }}
      sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', p: 2 }}
    >
      <Fade
        in
        timeout={reducedMotion ? 0 : undefined}
        // The answer most people want has the focus from the start.
        onEntering={() => startRef.current?.focus()}
      >
        <Box
          role="dialog"
          aria-modal="true"
          aria-labelledby={TITLE_ID}
          aria-describedby={TEXT_ID}
          tabIndex={-1}
          sx={{
            // Above the fixed backdrop, which a static card would lie under.
            position: 'relative',
            width: '100%',
            maxWidth: 440,
            bgcolor: 'background.paper',
            border: 1,
            borderColor: 'divider',
            borderRadius: `${radius.md}px`,
            boxShadow: shadow.overlay,
            outline: 'none',
            px: { xs: 3, sm: 4 },
            pt: 3.5,
            pb: 3,
          }}
        >
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
            Ein Rundgang in {count} Schritten zeigt, wie Sie fragen, worauf sich Antworten stützen
            und wo Sie Wissen finden.
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
          <Box
            sx={{
              display: 'flex',
              justifyContent: 'flex-end',
              gap: 1,
              mt: 4,
              // Narrow screens stack the answers, the start on top, both over the full width.
              flexDirection: { xs: 'column-reverse', sm: 'row' },
              alignItems: 'stretch',
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
          </Box>
        </Box>
      </Fade>
    </Modal>
  )
}
