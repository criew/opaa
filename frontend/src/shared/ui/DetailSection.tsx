import { useId, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'

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
    <Box component="section" id={id} aria-labelledby={headId} sx={{ mb: 4, scrollMarginTop: 16 }}>
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

/**
 * The text button of a section head, of a list row or of a run: no padding, no frame, at the right
 * edge like „Ändern“ in the rows of the Reiter „Quelle“.
 */
export function TextAction({
  children,
  onClick,
  disabled,
  ...aria
}: {
  children: ReactNode
  onClick: () => void
  disabled?: boolean
  'aria-label'?: string
  'aria-expanded'?: boolean
  'aria-controls'?: string
}) {
  return (
    <Link
      component="button"
      type="button"
      underline="hover"
      onClick={onClick}
      disabled={disabled}
      {...aria}
      sx={{
        fontSize: 13.5,
        fontWeight: 500,
        minHeight: 24,
        verticalAlign: 'baseline',
        '&:disabled': { color: 'text.disabled', cursor: 'default', textDecoration: 'none' },
      }}
    >
      {children}
    </Link>
  )
}
