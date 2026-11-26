import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import useMediaQuery from '@mui/material/useMediaQuery'
import useFullScreenDialog from '@/shared/ui/dialog/useFullScreenDialog'
import {
  FindingBar,
  FindingCard,
  StatusText,
  type Finding,
  type FindingStatus,
} from './FindingCard'

/** From this viewport width on, the column beside the document can be dragged wider. */
export const RESIZABLE_FROM_PX = 1440
export const COLUMN_DEFAULT_PX = 320
export const COLUMN_MIN_PX = 260
export const COLUMN_MAX_PX = 560
const COLUMN_STEP_PX = 24

export interface ReadingLayoutProps {
  /** `null` without a citation (opened from the library): then no card, only the panels. */
  finding: Finding | null
  status: FindingStatus
  /** Scrolls back to the marked passage; absent while there is none. */
  onJump?: () => void
  /** Further surfaces below the card: the outline, the recognized text of a scan. */
  panels?: ReactNode
  /** The document area; it brings its own scrolling. */
  children: ReactNode
}

/**
 * The reading mode's arrangement: the finding card left of the document, from 1440 px with a
 * separator that sets the column's width by pointer or arrow keys. Below 600 px the card is one
 * line above the document that unfolds. Every arrangement holds exactly one status line.
 */
export default function ReadingLayout({
  finding,
  status,
  onJump,
  panels,
  children,
}: ReadingLayoutProps) {
  const phone = useFullScreenDialog()
  const resizable = useMediaQuery(`(min-width:${RESIZABLE_FROM_PX}px)`)
  const [width, setWidth] = useState(COLUMN_DEFAULT_PX)
  const columnId = useId()
  const hasColumn = finding != null || Boolean(panels)

  if (phone) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
        {hasColumn ? (
          <FindingBar finding={finding} status={status} onJump={onJump} panels={panels} />
        ) : (
          <Box sx={{ px: 2 }}>
            <StatusText status={status} hidden />
          </Box>
        )}
        {children}
      </Box>
    )
  }

  return (
    <Box sx={{ display: 'flex', flex: 1, minHeight: 0 }}>
      {hasColumn && (
        <Box
          id={columnId}
          sx={{
            width: resizable ? width : { sm: 280, md: COLUMN_DEFAULT_PX },
            flex: 'none',
            overflow: 'auto',
            p: 2,
            pt: 1,
            display: 'flex',
            flexDirection: 'column',
            gap: 2,
          }}
        >
          {finding && <FindingCard finding={finding} status={status} onJump={onJump} />}
          {panels}
        </Box>
      )}
      {hasColumn && resizable && (
        <WidthSeparator controls={columnId} width={width} onWidth={setWidth} />
      )}
      <Box sx={{ display: 'flex', flexDirection: 'column', flex: 1, minWidth: 0, minHeight: 0 }}>
        {!finding && (
          <Box sx={{ px: 3 }}>
            <StatusText status={status} hidden />
          </Box>
        )}
        {children}
      </Box>
    </Box>
  )
}

function clampWidth(value: number): number {
  return Math.min(COLUMN_MAX_PX, Math.max(COLUMN_MIN_PX, Math.round(value)))
}

/** The window splitter between column and document (WAI-ARIA separator pattern). */
function WidthSeparator({
  controls,
  width,
  onWidth,
}: {
  controls: string
  width: number
  onWidth: (width: number) => void
}) {
  const drag = useRef<{ x: number; width: number } | null>(null)

  function onKeyDown(event: KeyboardEvent) {
    const next =
      event.key === 'ArrowRight'
        ? width + COLUMN_STEP_PX
        : event.key === 'ArrowLeft'
          ? width - COLUMN_STEP_PX
          : event.key === 'Home'
            ? COLUMN_MIN_PX
            : event.key === 'End'
              ? COLUMN_MAX_PX
              : null
    if (next === null) return
    event.preventDefault()
    onWidth(clampWidth(next))
  }

  return (
    <Box
      role="separator"
      aria-orientation="vertical"
      aria-label="Breite der Fundstellen-Spalte"
      aria-controls={controls}
      aria-valuemin={COLUMN_MIN_PX}
      aria-valuemax={COLUMN_MAX_PX}
      aria-valuenow={width}
      tabIndex={0}
      onKeyDown={onKeyDown}
      onPointerDown={(event) => {
        drag.current = { x: event.clientX, width }
        event.currentTarget.setPointerCapture?.(event.pointerId)
      }}
      onPointerMove={(event) => {
        if (!drag.current) return
        onWidth(clampWidth(drag.current.width + event.clientX - drag.current.x))
      }}
      onPointerUp={() => {
        drag.current = null
      }}
      onPointerCancel={() => {
        drag.current = null
      }}
      sx={{
        flex: 'none',
        width: 12,
        mx: -0.75,
        cursor: 'col-resize',
        touchAction: 'none',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1,
        '&::after': {
          content: '""',
          width: 4,
          height: 48,
          borderRadius: 2,
          bgcolor: 'text.disabled',
        },
        '&:hover::after, &:focus-visible::after': { bgcolor: 'primary.main' },
      }}
    />
  )
}
