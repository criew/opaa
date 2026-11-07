import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Modal from '@mui/material/Modal'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import visuallyHidden from '@mui/utils/visuallyHidden'
import DialogTitleWithClose from '@/shared/ui/DialogTitleWithClose'
import { findTourAnchor, focusChatInput } from '@/shared/ui/a11y/tourAnchor'
import { motion, radius, shadow } from '@/shared/theme/tokens'
import AnswerMiniature from './AnswerMiniature'
import { useTourAudience } from './quickTourActions'
import { useQuickTourStore } from './quickTourStore'
import { placeCard, sameRect, toCutout, type Rect } from './tourPlacement'
import { tourStepsFor } from './tourSteps'

const CARD_WIDTH = 340
const GUTTER = 16
const CUTOUT_PAD = 6
const TITLE_ID = 'quick-tour-title'
const TEXT_ID = 'quick-tour-text'

/**
 * The quick tour over the real interface: the scrim of a floating layer with a cutout around the
 * element a station explains, ringed in the accent, and a card beside it. The card is a modal
 * dialog - focus stays in it, ← and → page, Escape and ✕ end it. Every way out ends in the chat
 * input. Nothing about the tour leaves this tab.
 */
export default function QuickTour() {
  const open = useQuickTourStore((s) => s.open)
  const step = useQuickTourStore((s) => s.step)
  const goTo = useQuickTourStore((s) => s.goTo)
  const close = useQuickTourStore((s) => s.close)
  // The opening is read out as the dialog's name and description; only a change is announced.
  const paged = useQuickTourStore((s) => s.paged)
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)')
  const steps = tourStepsFor(useTourAudience())
  const index = Math.min(step, steps.length - 1)
  const current = steps[index]
  const isLast = index === steps.length - 1

  const cardRef = useRef<HTMLDivElement>(null)
  const primaryRef = useRef<HTMLButtonElement>(null)
  const modalRef = useRef<HTMLDivElement>(null)
  const [cutout, setCutout] = useState<Rect | null>(null)
  const [cardPos, setCardPos] = useState<{ left: number; top: number } | null>(null)

  const measure = useCallback(() => {
    const element = findTourAnchor(current.anchor)
    element?.scrollIntoView?.({ block: 'nearest' })
    const next = element ? toCutout(element.getBoundingClientRect(), CUTOUT_PAD) : null
    setCutout((previous) => (sameRect(previous, next) ? previous : next))
    const keep = current.keepVisible ? findTourAnchor(current.keepVisible) : null
    const keepTop = keep?.getBoundingClientRect().top
    const ceiling = next && keepTop !== undefined ? Math.min(next.top, keepTop) : null
    const card = cardRef.current
    if (!card) return
    const pos = placeCard(next, { width: card.offsetWidth, height: card.offsetHeight }, ceiling, {
      gutter: GUTTER,
      pad: CUTOUT_PAD,
    })
    setCardPos((previous) =>
      previous?.left === pos.left && previous.top === pos.top ? previous : pos,
    )
  }, [current])

  // Measured after layout, and again whenever the page changes beneath the tour - a chat page that
  // is still loading brings its anchors later.
  useLayoutEffect(() => {
    if (!open) return
    let frame = window.requestAnimationFrame(measure)
    const remeasure = () => {
      window.cancelAnimationFrame(frame)
      frame = window.requestAnimationFrame(measure)
    }
    const observer = new MutationObserver(remeasure)
    observer.observe(document.body, { childList: true, subtree: true })
    window.addEventListener('resize', remeasure)
    return () => {
      window.cancelAnimationFrame(frame)
      observer.disconnect()
      window.removeEventListener('resize', remeasure)
    }
  }, [open, index, measure])

  // The modal's own trap re-subscribes on every render of the modal and misses a focus the page
  // sets in that commit (the chat arriving after a route change). This guard lives as long as the
  // tour is open; the trap's sentinels belong to the modal root and keep their Tab cycling.
  useEffect(() => {
    if (!open) return
    const guard = (event: FocusEvent) => {
      const root = modalRef.current
      if (root && event.target instanceof Node && !root.contains(event.target)) {
        primaryRef.current?.focus()
      }
    }
    document.addEventListener('focusin', guard)
    return () => document.removeEventListener('focusin', guard)
  }, [open])

  // Keyboard users keep their place on the primary button from station to station.
  useEffect(() => {
    if (open) primaryRef.current?.focus()
  }, [open, index])

  if (!open) return null

  const end = () => {
    close()
    window.setTimeout(focusChatInput, 0)
  }
  const page = (to: number) => goTo(to)
  const next = () => (isLast ? end() : page(index + 1))
  const back = () => {
    if (index > 0) page(index - 1)
  }

  const slide = reducedMotion ? 'none' : `transform ${motion.durationSlowMs}ms ${motion.easeOut}`

  return (
    <Modal ref={modalRef} open onClose={end} hideBackdrop disableRestoreFocus>
      <Box
        role="dialog"
        aria-modal="true"
        aria-labelledby={TITLE_ID}
        aria-describedby={TEXT_ID}
        // The modal's focus trap focuses this container on opening and whenever focus escapes
        // (a page behind the tour focusing its own field); it is handed on to the primary button.
        onFocus={(event) => {
          if (event.target === event.currentTarget) primaryRef.current?.focus()
        }}
        onKeyDown={(event) => {
          // The last station ends only on its button, never on an arrow key.
          if (event.key === 'ArrowRight' && !isLast) page(index + 1)
          if (event.key === 'ArrowLeft') back()
        }}
        sx={{ position: 'fixed', inset: 0, outline: 'none' }}
      >
        {cutout ? (
          <Box
            aria-hidden
            data-testid="quick-tour-cutout"
            sx={{
              position: 'fixed',
              left: 0,
              top: 0,
              width: cutout.width,
              height: cutout.height,
              transform: `translate(${cutout.left}px, ${cutout.top}px)`,
              transition: slide,
              borderRadius: `${radius.md}px`,
              // The page around the cutout recedes under the scrim of every floating layer.
              boxShadow: (theme) => `0 0 0 9999px ${theme.palette.background.scrim}`,
              outline: (theme) => `2px solid ${theme.palette.primary.main}`,
              outlineOffset: '2px',
              pointerEvents: 'none',
            }}
          />
        ) : (
          <Box aria-hidden sx={{ position: 'fixed', inset: 0, bgcolor: 'background.scrim' }} />
        )}
        <Box
          ref={cardRef}
          sx={{
            position: 'fixed',
            left: 0,
            top: 0,
            width: `min(${CARD_WIDTH}px, calc(100vw - ${2 * GUTTER}px))`,
            maxHeight: `calc(100dvh - ${2 * GUTTER}px)`,
            overflowY: 'auto',
            transform: cardPos ? `translate(${cardPos.left}px, ${cardPos.top}px)` : 'none',
            // Invisible until placed, but focusable: the focus lands on "Weiter" right away.
            opacity: cardPos ? 1 : 0,
            transition: slide,
            bgcolor: 'background.paper',
            border: 1,
            borderColor: 'divider',
            borderRadius: `${radius.md}px`,
            boxShadow: shadow.overlay,
            pb: 2.5,
          }}
        >
          <DialogTitleWithClose id={TITLE_ID} onClose={end}>
            {current.title}
          </DialogTitleWithClose>
          <Box sx={{ px: 3, display: 'flex', flexDirection: 'column', gap: 2 }}>
            {current.withAnswerSample && <AnswerMiniature />}
            <Typography id={TEXT_ID} variant="body2" sx={{ color: 'text.secondary' }}>
              {current.text}
            </Typography>
          </Box>
          <Box sx={{ px: 3, pt: 2.5, display: 'flex', alignItems: 'center', gap: 1 }}>
            <Typography variant="caption" sx={{ color: 'text.secondary', mr: 'auto' }}>
              {index + 1} von {steps.length}
            </Typography>
            {index > 0 && (
              <Button variant="text" onClick={back}>
                Zurück
              </Button>
            )}
            <Button ref={primaryRef} variant="contained" onClick={next}>
              {isLast ? 'Fertig' : 'Weiter'}
            </Button>
          </Box>
          <Box role="status" aria-live="polite" sx={visuallyHidden}>
            {paged
              ? `Schritt ${index + 1} von ${steps.length}: ${current.title}. ${current.text}`
              : ''}
          </Box>
        </Box>
      </Box>
    </Modal>
  )
}
