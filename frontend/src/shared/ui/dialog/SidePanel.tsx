import type { ReactNode, Ref } from 'react'
import Box from '@mui/material/Box'
import Drawer from '@mui/material/Drawer'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import { DIALOG_TITLE_SX } from './dialogWidth'

/** One width for every side panel; below 600 px viewport width it fills the screen. */
const SIDE_PANEL_WIDTH = 560

export interface SidePanelProps {
  open: boolean
  /** The name of the object the panel shows or edits. */
  title: ReactNode
  /** The id of the title; the panel is named by it. */
  titleId: string
  /** Receives the focus where the panel moves it to its title. */
  titleRef?: Ref<HTMLHeadingElement>
  /** The ✕ (view), Escape and a click beside the panel. Not called while `busy`. */
  onClose: () => void
  /** True while a change runs: the panel then stays open. */
  busy?: boolean
  /**
   * The panel currently is a form with its own „Abbrechen“: like a form dialog it then carries
   * no ✕ (guidelines 5.6).
   */
  form?: boolean
  /** A step back inside the panel, above the title. */
  before?: ReactNode
  /** Badges, a line of state or a link under the title. */
  below?: ReactNode
  /** The content; it brings its own scrolling area (`flex: 1`, `overflowY: 'auto'`). */
  children: ReactNode
}

/**
 * A panel at the right edge for the one case a dialog does not fit: the list behind it is needed
 * while working (comparing, changing several entries in a row; guidelines 5.6). It follows the
 * rules of the dialogs: the title names the object, a view closes through the ✕ without a button
 * „Schließen“, a form through „Abbrechen“ without a ✕, and no divider stands at the title.
 */
export default function SidePanel({
  open,
  title,
  titleId,
  titleRef,
  onClose,
  busy = false,
  form = false,
  before,
  below,
  children,
}: SidePanelProps) {
  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={busy ? undefined : onClose}
      slotProps={{
        paper: {
          role: 'dialog',
          'aria-labelledby': titleId,
          sx: {
            width: { xs: '100%', sm: SIDE_PANEL_WIDTH },
            display: 'flex',
            flexDirection: 'column',
          },
        },
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1, px: 3, pt: 2.5, pb: 1 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          {before}
          <Typography
            id={titleId}
            ref={titleRef}
            tabIndex={titleRef ? -1 : undefined}
            component="h2"
            sx={{ ...DIALOG_TITLE_SX, outline: 'none' }}
          >
            {title}
          </Typography>
          {below}
        </Box>
        {!form && (
          <Tooltip title="Schließen">
            {/* the span keeps the tooltip working while the button is disabled */}
            <Box component="span" sx={{ mt: -0.5 }}>
              <IconButton aria-label="Schließen" onClick={onClose} disabled={busy}>
                <CloseIcon />
              </IconButton>
            </Box>
          </Tooltip>
        )}
      </Box>
      {children}
    </Drawer>
  )
}
