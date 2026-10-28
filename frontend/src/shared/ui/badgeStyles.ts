import type { Theme } from '@mui/material/styles'
import { badgeShape, darkRoles, lightRoles } from '../theme/tokens'

/** `border-strong` of the active scheme - the outline of every label (guidelines 5.5). */
export const badgeBorderColor = (theme: Theme) =>
  (theme.palette.mode === 'dark' ? darkRoles : lightRoles).borderStrong

/**
 * The quiet outlined label of guidelines 5.5: pill, 12 px, weight 500, `border-strong` outline and
 * `fg-2` text - never an accent or signal colour. Shared by `MetaBadge` and every hand-built
 * label with an icon, so they cannot drift apart.
 */
export const outlinedBadgeSx = {
  color: 'text.secondary',
  border: 1,
  borderColor: badgeBorderColor,
  ...badgeShape,
  whiteSpace: 'nowrap',
} as const
