import { createPortal } from 'react-dom'
import AddIcon from '@mui/icons-material/Add'
import Box from '@mui/material/Box'
import Button, { type ButtonProps } from '@mui/material/Button'
import Tooltip from '@mui/material/Tooltip'
import { useAreaHeaderSlot } from './areaHeaderSlots'

interface CreateButtonProps extends Omit<
  ButtonProps,
  'variant' | 'startIcon' | 'size' | 'color' | 'children'
> {
  /** Names the object: „Konto anlegen“, „Neuer Zugang“ (guidelines 5.1). */
  children: string
  /**
   * `header` (default): the page's primary action, shown in the page's header - at the right
   * edge of `AreaPageHeader`, among the actions of a detail page's header. `empty`: the same
   * action inside an empty state, secondary and in place.
   */
  placement?: 'header' | 'empty'
  /** Why the button is disabled, as a tooltip; only shown while `disabled`. */
  disabledReason?: string
}

/**
 * The button that creates a new object, the only one with a plus icon outside the sidebar
 * (guidelines 5.1). Written in the page or in the content of a tab, it appears in the page's
 * header, so it changes with the tab and never stands beside a filter row or an explanation.
 * Rendered on its own, without a header, it stays in place.
 */
export default function CreateButton({
  children,
  placement = 'header',
  disabledReason,
  disabled,
  ...buttonProps
}: CreateButtonProps) {
  const slot = useAreaHeaderSlot('action')
  const button = (
    <Button
      {...buttonProps}
      // found by e2e/ui-lab/mobile-pass.mjs, which measures the create button on the phone
      data-create-button=""
      disabled={disabled}
      variant={placement === 'header' ? 'contained' : 'outlined'}
      startIcon={<AddIcon />}
    >
      {children}
    </Button>
  )
  // A disabled button fires no pointer events, so the tooltip hangs on a span around it.
  const explained =
    disabled && disabledReason ? (
      <Tooltip title={disabledReason}>
        <Box component="span" sx={{ display: 'inline-flex' }}>
          {button}
        </Box>
      </Tooltip>
    ) : (
      button
    )
  if (placement === 'empty') return explained
  if (slot === undefined) return null
  return slot ? createPortal(explained, slot) : explained
}
