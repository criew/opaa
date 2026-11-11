import type { Breakpoint } from '@mui/material/styles'
import { fontSize } from '@/shared/theme/tokens'

/**
 * The widths a dialog may have (guidelines 5.6): 600 px for a form, 900 px for an editor with a
 * preview or a list, 444 px only for a confirmation. They are the theme's breakpoints `sm`, `md`
 * and `xs` as MUI's `maxWidth`.
 */
export type DialogWidth = 'form' | 'editor' | 'confirm'

export const DIALOG_MAX_WIDTH: Record<DialogWidth, Breakpoint> = {
  confirm: 'xs',
  form: 'sm',
  editor: 'md',
}

/** The title of a dialog or side panel: the type step `title`, never a size of its own. */
export const DIALOG_TITLE_SX = {
  fontSize: fontSize.title,
  fontWeight: 600,
  overflowWrap: 'anywhere',
} as const
