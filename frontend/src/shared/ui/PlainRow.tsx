import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'

/**
 * A quiet settings row - title | value | text action at the shared right edge - in the grid of
 * the summary rows of the Reiter „Quelle“. What the action opens stands below the value.
 */
export default function PlainRow({
  title,
  value,
  action,
  onAction,
  children,
  headingLevel = 'h2',
}: {
  title: string
  value: ReactNode
  /** The action's word, e.g. „Übergeben“; without it the row only shows its value. */
  action?: string
  onAction?: () => void
  /** Opened content, under the value column. */
  children?: ReactNode
  headingLevel?: 'h2' | 'h3'
}) {
  return (
    <Box
      component="section"
      aria-label={title}
      sx={{ borderBottom: 1, borderColor: 'divider', py: 2 }}
    >
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: 'minmax(0, 1fr) auto', sm: '128px minmax(0, 1fr) auto' },
          columnGap: 2,
          rowGap: 0.5,
          alignItems: 'baseline',
        }}
      >
        <Typography
          component={headingLevel}
          sx={{ fontSize: 15, fontWeight: 600, color: 'text.primary' }}
        >
          {title}
        </Typography>
        <Box
          sx={{
            gridColumn: { xs: '1 / -1', sm: '2' },
            gridRow: { xs: '2', sm: '1' },
            fontSize: 13.5,
            minWidth: 0,
          }}
        >
          {value}
        </Box>
        {action && onAction && (
          <Link
            component="button"
            type="button"
            underline="hover"
            onClick={onAction}
            sx={{
              fontSize: 13.5,
              fontWeight: 500,
              gridColumn: { xs: '2', sm: '3' },
              gridRow: '1',
              justifySelf: 'end',
            }}
          >
            {action}
            <Box component="span" sx={visuallyHidden}>
              {` ${title}`}
            </Box>
          </Link>
        )}
        {children && (
          <Box sx={{ gridColumn: { xs: '1 / -1', sm: '2 / -1' }, pt: 1.5 }}>{children}</Box>
        )}
      </Box>
    </Box>
  )
}
