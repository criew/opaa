import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Modal from '@mui/material/Modal'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { alpha, useTheme } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import { useAuthStore } from '@/shared/session/authStore'
import DialogTitleWithClose from '@/shared/ui/DialogTitleWithClose'
import { carbon, motion, navy, radius, shadow } from '@/shared/theme/tokens'
import { focusChatInput, stepsFor } from './tourSteps'
import { AnswerMiniature } from './TourMiniatures'
import { useTourStore } from './tourStore'

const CARD_WIDTH = 340
const GUTTER = 16
const CUTOUT_PAD = 6

interface Rect {
  top: number
  left: number
  width: number
  height: number
}

/** The anchor's box, kept inside the viewport so the ring around the cutout is never cut off. */
function clampToViewport(box: DOMRect): Rect {
  const edge = CUTOUT_PAD + 4
  const left = Math.max(edge, box.left)
  const top = Math.max(edge, box.top)
  const right = Math.min(window.innerWidth - edge, box.right)
  const bottom = Math.min(window.innerHeight - edge, box.bottom)
  return { left, top, width: right - left, height: bottom - top }
}

/**
 * Places the card beside a rail or column anchor, else below it, else above it. With a ceiling
 * (the top of an element the tour shows next) the card goes above that ceiling instead.
 */
function placeCard(
  anchor: Rect | null,
  card: { width: number; height: number },
  ceiling: number | null,
) {
  const vw = window.innerWidth
  const vh = window.innerHeight
  const clampX = (x: number) => Math.min(Math.max(GUTTER, x), vw - card.width - GUTTER)
  const clampY = (y: number) => Math.min(Math.max(GUTTER, y), vh - card.height - GUTTER)
  if (!anchor) return { left: clampX((vw - card.width) / 2), top: clampY((vh - card.height) / 2) }
  if (ceiling !== null) {
    return { left: clampX(anchor.left), top: clampY(ceiling - CUTOUT_PAD - GUTTER - card.height) }
  }
  const right = anchor.left + anchor.width + CUTOUT_PAD + GUTTER
  if (anchor.left < vw / 3 && right + card.width <= vw - GUTTER) {
    return { left: right, top: clampY(anchor.top - CUTOUT_PAD) }
  }
  const below = anchor.top + anchor.height + CUTOUT_PAD + GUTTER
  if (below + card.height <= vh - GUTTER) return { left: clampX(anchor.left), top: below }
  return {
    left: clampX(anchor.left),
    top: clampY(anchor.top - CUTOUT_PAD - GUTTER - card.height),
  }
}

/**
 * Variant B and the tour of D: the tour as coachmarks over the real interface. The
 * station's element stays visible in a cutout of the scrim, a card beside it explains it. The card
 * is a modal dialog - focus stays in it, Escape and the ✕ end the tour, arrow keys page.
 */
export default function CoachmarkTour() {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)')
  const isAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const { open, step, goTo, finish } = useTourStore()
  const steps = stepsFor(isAdmin, isDesktop)
  const current = steps[Math.min(step, steps.length - 1)]
  const text = current.text
  const isLast = step >= steps.length - 1

  const cardRef = useRef<HTMLDivElement>(null)
  const primaryRef = useRef<HTMLButtonElement>(null)
  const [anchor, setAnchor] = useState<Rect | null>(null)
  const [cardPos, setCardPos] = useState<{ left: number; top: number } | null>(null)

  const measure = useCallback(() => {
    const element = current.anchor()
    element?.scrollIntoView({ block: 'nearest' })
    const box = element?.getBoundingClientRect()
    const next = box && box.width > 0 ? clampToViewport(box) : null
    setAnchor(next)
    const keep = current.keepVisible?.()?.getBoundingClientRect()
    const ceiling = next && keep ? Math.min(next.top, keep.top) : null
    const card = cardRef.current
    if (card) {
      setCardPos(placeCard(next, { width: card.offsetWidth, height: card.offsetHeight }, ceiling))
    }
  }, [current])

  // Measured in the next frame: the card must be laid out before its size places it.
  useLayoutEffect(() => {
    if (!open) return
    const frame = window.requestAnimationFrame(measure)
    return () => window.cancelAnimationFrame(frame)
  }, [open, step, measure])

  useEffect(() => {
    if (!open) return
    window.addEventListener('resize', measure)
    return () => window.removeEventListener('resize', measure)
  }, [open, measure])

  // Keyboard users keep their place on the primary button from station to station.
  useEffect(() => {
    if (open) primaryRef.current?.focus()
  }, [open, step])

  if (!open) return null

  // Every way out - ✕, Escape, "Fertig" - ends in the chat input, where the person starts.
  const end = () => {
    finish()
    window.setTimeout(focusChatInput, 0)
  }
  const next = () => (isLast ? end() : goTo(step + 1))
  const back = () => step > 0 && goTo(step - 1)

  const isDark = theme.palette.mode === 'dark'
  // The scrim of every floating layer (theme MuiBackdrop), without its blur: the cutout must stay sharp.
  const scrim = isDark ? alpha(carbon[950], 0.72) : alpha(navy[900], 0.42)
  const slide = reducedMotion ? 'none' : `transform ${motion.durationSlowMs}ms ${motion.easeOut}`
  const titleId = 'tour-step-title'
  const textId = 'tour-step-text'

  return (
    <Modal open onClose={end} hideBackdrop>
      <Box
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={textId}
        onKeyDown={(event) => {
          // The last station ends only on its button, never on an arrow key.
          if (event.key === 'ArrowRight' && !isLast) next()
          if (event.key === 'ArrowLeft') back()
        }}
        sx={{ position: 'fixed', inset: 0, outline: 'none' }}
      >
        {anchor ? (
          <Box
            aria-hidden
            sx={{
              position: 'fixed',
              left: 0,
              top: 0,
              width: anchor.width + 2 * CUTOUT_PAD,
              height: anchor.height + 2 * CUTOUT_PAD,
              transform: `translate(${anchor.left - CUTOUT_PAD}px, ${anchor.top - CUTOUT_PAD}px)`,
              transition: slide,
              borderRadius: `${radius.md}px`,
              boxShadow: `0 0 0 9999px ${scrim}`,
              outline: `2px solid ${theme.palette.primary.main}`,
              outlineOffset: '2px',
              pointerEvents: 'none',
            }}
          />
        ) : (
          <Box aria-hidden sx={{ position: 'fixed', inset: 0, bgcolor: scrim }} />
        )}
        <Box
          ref={cardRef}
          sx={{
            position: 'fixed',
            left: 0,
            top: 0,
            width: `min(${CARD_WIDTH}px, calc(100vw - ${2 * GUTTER}px))`,
            transform: cardPos ? `translate(${cardPos.left}px, ${cardPos.top}px)` : 'none',
            visibility: cardPos ? 'visible' : 'hidden',
            transition: slide,
            bgcolor: 'background.paper',
            border: 1,
            borderColor: 'divider',
            borderRadius: `${radius.md}px`,
            boxShadow: shadow.overlay,
            pb: 2.5,
          }}
        >
          <DialogTitleWithClose id={titleId} onClose={end}>
            {current.title}
          </DialogTitleWithClose>
          <Box sx={{ px: 3, display: 'flex', flexDirection: 'column', gap: 2 }}>
            {current.withAnswerSample && <AnswerMiniature compact />}
            <Typography id={textId} variant="body2" sx={{ color: 'text.secondary' }}>
              {text}
            </Typography>
          </Box>
          <Box sx={{ px: 3, pt: 2.5, display: 'flex', alignItems: 'center', gap: 1 }}>
            <Typography variant="caption" sx={{ color: 'text.secondary', mr: 'auto' }}>
              {step + 1} von {steps.length}
            </Typography>
            {step > 0 && (
              <Button variant="text" onClick={back}>
                Zurück
              </Button>
            )}
            <Button ref={primaryRef} variant="contained" onClick={next}>
              {isLast ? 'Fertig' : 'Weiter'}
            </Button>
          </Box>
          <Box role="status" aria-live="polite" sx={visuallyHidden}>
            {`Schritt ${step + 1} von ${steps.length}: ${current.title}. ${text}`}
          </Box>
        </Box>
      </Box>
    </Modal>
  )
}
