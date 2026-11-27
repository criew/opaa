import { useLayoutEffect, useState } from 'react'
import Box from '@mui/material/Box'
import { passageLabelSx } from './styles'

/** Margin left of a block that holds the label beside its edge stroke. */
const MARGIN_ROOM_PX = 88

/**
 * The label „Fundstelle“ once beside the first block of the passage, as a text element of its own:
 * placed over `container` (positioned) in the margin left of the block's edge stroke, or above
 * the block where the margin is too narrow, following every change of size.
 * Hidden from screen readers, which hear the status line instead.
 */
export default function PassageLabel({
  block,
  container,
}: {
  block: HTMLElement | null
  container: HTMLElement | null
}) {
  const [measured, setMeasured] = useState<{
    of: HTMLElement
    top: number
    left: number
  } | null>(null)

  useLayoutEffect(() => {
    if (!block || !container) return
    const measure = () => {
      const outer = container.getBoundingClientRect()
      const inner = block.getBoundingClientRect()
      setMeasured({
        of: block,
        top: inner.top - outer.top + container.scrollTop,
        left: inner.left - outer.left + container.scrollLeft,
      })
    }
    const frame = requestAnimationFrame(measure)
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure)
    observer?.observe(container)
    return () => {
      cancelAnimationFrame(frame)
      observer?.disconnect()
    }
  }, [block, container])

  const place = measured?.of === block ? measured : null
  if (!block || !place) return null
  return (
    <Box
      component="span"
      aria-hidden="true"
      data-testid="passage-label"
      sx={[
        passageLabelSx,
        place.left >= MARGIN_ROOM_PX
          ? // in the margin, ending just left of the edge stroke, level with the block's top
            { top: place.top, left: place.left - 18, transform: 'translateX(-100%)' }
          : // above the block where the margin is too narrow (a phone)
            { top: place.top, left: place.left - 12, transform: 'translateY(calc(-100% - 4px))' },
      ]}
    >
      Fundstelle
    </Box>
  )
}
