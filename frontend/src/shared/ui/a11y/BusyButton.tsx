import type { MouseEvent } from 'react'
import Box from '@mui/material/Box'
import Button, { type ButtonProps } from '@mui/material/Button'
import visuallyHidden from '@mui/utils/visuallyHidden'

interface BusyButtonProps extends Omit<ButtonProps, 'aria-busy'> {
  /** True while the request started by this button is in flight. */
  busy: boolean
  /** Announced to assistive technology when the request starts. */
  busyAnnouncement: string
  /**
   * Not usable right now, without a request in flight: marked and swallowed like `busy`, but not
   * announced as busy. Unlike `disabled` it keeps the keyboard focus on the button.
   */
  unavailable?: boolean
}

/**
 * A button that stays focusable while its request runs: it is marked `aria-disabled` instead of
 * `disabled` (which would drop the keyboard focus), a click or key press while busy is swallowed,
 * and the busy state is announced through an always-present polite status region. `unavailable`
 * marks and swallows the same way without the busy state.
 */
export default function BusyButton({
  busy,
  busyAnnouncement,
  unavailable = false,
  onClick,
  sx,
  children,
  ...rest
}: BusyButtonProps) {
  const inert = busy || unavailable

  function handleClick(event: MouseEvent<HTMLButtonElement>) {
    if (inert) {
      event.preventDefault()
      return
    }
    onClick?.(event)
  }

  return (
    <>
      <Button
        {...rest}
        aria-disabled={inert || undefined}
        aria-busy={busy || undefined}
        onClick={handleClick}
        sx={[
          inert && {
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
