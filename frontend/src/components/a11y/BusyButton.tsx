import type { MouseEvent } from 'react'
import Box from '@mui/material/Box'
import Button, { type ButtonProps } from '@mui/material/Button'
import visuallyHidden from '@mui/utils/visuallyHidden'

interface BusyButtonProps extends Omit<ButtonProps, 'aria-busy'> {
  /** True while the request started by this button is in flight. */
  busy: boolean
  /** Announced to assistive technology when the request starts. */
  busyAnnouncement: string
}

/**
 * A button that stays focusable while its request runs: it is marked `aria-disabled` instead of
 * `disabled` (which would drop the keyboard focus), a click or key press while busy is swallowed,
 * and the busy state is announced through an always-present polite status region.
 */
export default function BusyButton({
  busy,
  busyAnnouncement,
  onClick,
  sx,
  children,
  ...rest
}: BusyButtonProps) {
  function handleClick(event: MouseEvent<HTMLButtonElement>) {
    if (busy) {
      event.preventDefault()
      return
    }
    onClick?.(event)
  }

  return (
    <>
      <Button
        {...rest}
        aria-disabled={busy || undefined}
        aria-busy={busy || undefined}
        onClick={handleClick}
        sx={[
          busy && {
            color: 'action.disabled',
            borderColor: 'action.disabledBackground',
            cursor: 'default',
            '&.MuiButton-contained, &.MuiButton-contained:hover': {
              backgroundColor: 'action.disabledBackground',
              boxShadow: 'none',
            },
            '&.MuiButton-outlined:hover, &.MuiButton-text:hover': {
              backgroundColor: 'transparent',
            },
          },
          ...(Array.isArray(sx) ? sx : [sx]),
        ]}
      >
        {children}
      </Button>
      <Box component="span" role="status" aria-live="polite" sx={visuallyHidden}>
        {busy ? busyAnnouncement : ''}
      </Box>
    </>
  )
}
