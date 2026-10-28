import Typography from '@mui/material/Typography'
import { outlinedBadgeSx } from './badgeStyles'

interface MetaBadgeProps {
  children: string
  /** Breaks onto a second line where the column is narrower than the label (table cells). */
  wrap?: boolean
  /** A fuller name for assistive technology where the short word needs its context. */
  ariaLabel?: string
}

/**
 * The quiet text label of guidelines 5.5 (role, origin, kind, notes in lists): outline in
 * `border-strong`, text in `fg-2`, never an accent or signal colour - the word carries the
 * meaning. It reads the same as the theme's outlined `Chip`.
 */
export default function MetaBadge({ children, wrap = false, ariaLabel }: MetaBadgeProps) {
  return (
    <Typography
      component="span"
      {...(ariaLabel && { role: 'img', 'aria-label': ariaLabel })}
      sx={{
        ...outlinedBadgeSx,
        px: 1,
        py: 0.25,
        ...(wrap && { display: 'inline-block', maxWidth: '100%', whiteSpace: 'normal' }),
      }}
    >
      {children}
    </Typography>
  )
}
