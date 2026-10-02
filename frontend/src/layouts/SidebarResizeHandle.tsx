import { useRef } from 'react'
import type { KeyboardEvent, PointerEvent } from 'react'
import Box from '@mui/material/Box'
import {
  SIDEBAR_DEFAULT_WIDTH,
  SIDEBAR_MAX_WIDTH,
  SIDEBAR_MIN_WIDTH,
  useUiStore,
} from '../stores/uiStore'

const KEYBOARD_STEP = 16

/**
 * Drag handle on the right edge of the space column. Dragging, the arrow keys and Home/End change
 * the column width; a double click restores the default. The store clamps every value, so the
 * handle never has to.
 */
export default function SidebarResizeHandle() {
  const width = useUiStore((s) => s.sidebarWidth)
  const setWidth = useUiStore((s) => s.setSidebarWidth)
  const drag = useRef<{ startX: number; startWidth: number } | null>(null)

  const onPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return
    event.preventDefault()
    event.currentTarget.setPointerCapture?.(event.pointerId)
    drag.current = { startX: event.clientX, startWidth: width }
  }

  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (!drag.current) return
    setWidth(drag.current.startWidth + event.clientX - drag.current.startX)
  }

  const endDrag = (event: PointerEvent<HTMLDivElement>) => {
    if (!drag.current) return
    drag.current = null
    event.currentTarget.releasePointerCapture?.(event.pointerId)
  }

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const next = {
      ArrowLeft: width - KEYBOARD_STEP,
      ArrowRight: width + KEYBOARD_STEP,
      Home: SIDEBAR_MIN_WIDTH,
      End: SIDEBAR_MAX_WIDTH,
    }[event.key]
    if (next === undefined) return
    event.preventDefault()
    setWidth(next)
  }

  return (
    <Box
      role="separator"
      aria-orientation="vertical"
      aria-label="Breite der Space-Spalte"
      aria-valuenow={width}
      aria-valuemin={SIDEBAR_MIN_WIDTH}
      aria-valuemax={SIDEBAR_MAX_WIDTH}
      tabIndex={0}
      title="Ziehen zum Ändern der Breite, Doppelklick setzt zurück"
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
      onLostPointerCapture={() => {
        drag.current = null
      }}
      onDoubleClick={() => setWidth(SIDEBAR_DEFAULT_WIDTH)}
      onKeyDown={onKeyDown}
      sx={{
        position: 'absolute',
        top: 0,
        right: -4,
        bottom: 0,
        width: 8,
        zIndex: 1,
        cursor: 'col-resize',
        touchAction: 'none',
        // The visible cue is a thin line centred on the column edge, shown on hover and while dragging.
        '&::after': {
          content: '""',
          position: 'absolute',
          top: 0,
          bottom: 0,
          left: 3,
          width: 2,
          bgcolor: 'primary.main',
          opacity: 0,
          transition: 'opacity 120ms',
        },
        '&:hover::after, &:active::after': { opacity: 1 },
        // Keyboard focus keeps the design system's focus ring, distinct from the hover cue.
        '&:focus-visible': {
          outline: '2px solid',
          outlineColor: 'primary.main',
          outlineOffset: -2,
        },
      }}
    />
  )
}
