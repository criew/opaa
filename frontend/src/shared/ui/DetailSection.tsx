import { useId, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import type { SxProps, Theme } from '@mui/material/styles'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'

interface DetailSectionProps {
  /** The section's head in running type, weight 600 - the style of the rows of the Reiter „Quelle". */
  title: string
  /** The one adding action („Freigeben“, „Feld anlegen“) as a text button at the right edge. */
  action?: ReactNode
  headingLevel?: 'h2' | 'h3'
  /** An anchor the page can jump to. */
  id?: string
  children: ReactNode
}

/**
 * A section of a detail page tab: head and adding action on one baseline, the content below, no
 * frame and no eyebrow. Every tab of a detail page uses it, so heads and the right edge of their
 * actions stay the same from tab to tab.
 */
export default function DetailSection({
  title,
  action,
  headingLevel = 'h2',
  id,
  children,
}: DetailSectionProps) {
  const headId = useId()
  return (
    <Box
      component="section"
      id={id}
      aria-labelledby={headId}
      sx={{ mb: 4, scrollMarginTop: { xs: 72, sm: 16 } }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'baseline',
          justifyContent: 'space-between',
          gap: 2,
          minHeight: 32,
          mb: 1,
        }}
      >
        <Typography
          id={headId}
          component={headingLevel}
          sx={{ fontSize: 15, fontWeight: 600, color: 'text.primary' }}
        >
          {title}
        </Typography>
        {action && <Box sx={{ flex: 'none' }}>{action}</Box>}
      </Box>
      {children}
    </Box>
  )
}

/** The type of a text action and a free-standing text link: one look for both (Leitlinie 5.1). */
const TEXT_ACTION_SX = {
  fontSize: 13.5,
  fontWeight: 500,
  textAlign: 'left',
  minHeight: 24,
  minWidth: 24,
  verticalAlign: 'baseline',
} as const

function withSx(sx: SxProps<Theme> | undefined, own: SxProps<Theme>): SxProps<Theme> {
  return [own, ...(Array.isArray(sx) ? sx : [sx])] as SxProps<Theme>
}

/**
 * The text button of a section head, of a list row or of a run: no padding, no frame, at the right
 * edge like „Ändern“ in the rows of the Reiter „Quelle“. It acts on the page; what leads elsewhere
 * is a {@link TextLink}.
 */
export function TextAction({
  children,
  context,
  onClick,
  disabled,
  sx,
  ...aria
}: {
  children: ReactNode
  /** Said after the visible word for assistive technology only („Ändern“ + „ Feld Aktenzeichen“). */
  context?: string
  onClick: () => void
  disabled?: boolean
  /** Placement only (grid cell, margin, alignment); the type stays that of every text action. */
  sx?: SxProps<Theme>
  'aria-expanded'?: boolean
  'aria-controls'?: string
  'aria-describedby'?: string
  'aria-label'?: string
}) {
  return (
    <Link
      component="button"
      type="button"
      underline="hover"
      onClick={onClick}
      disabled={disabled}
      {...aria}
      sx={withSx(sx, {
        ...TEXT_ACTION_SX,
        '&:disabled': { color: 'text.disabled', cursor: 'default', textDecoration: 'none' },
      })}
    >
      {children}
      {context && (
        <Box component="span" sx={visuallyHidden}>
          {` ${context}`}
        </Box>
      )}
    </Link>
  )
}

/**
 * A free-standing link to another place, in the type of {@link TextAction}. A link inside a
 * sentence is a plain `Link` in the sentence's type instead, underlined throughout.
 */
export function TextLink({
  to,
  state,
  replace,
  children,
  sx,
}: {
  to: string
  state?: unknown
  replace?: boolean
  children: ReactNode
  /** Placement only (margin, display); the type stays that of every text link. */
  sx?: SxProps<Theme>
}) {
  return (
    <Link
      component={RouterLink}
      to={to}
      state={state}
      replace={replace}
      underline="hover"
      sx={withSx(sx, TEXT_ACTION_SX)}
    >
      {children}
    </Link>
  )
}
