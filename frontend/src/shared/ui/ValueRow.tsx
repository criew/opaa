import { useId, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'

/** The grid of the rows of the Reiter „Quelle“: title | content | action. */
const ROW_GRID = {
  display: 'grid',
  gridTemplateColumns: { xs: 'minmax(0, 1fr) auto', sm: '128px minmax(0, 1fr) auto' },
  columnGap: 2,
  rowGap: 0.5,
  alignItems: 'baseline',
} as const

/**
 * A single value as one row of the Reiter „Quelle“: title | value | one text action at the shared
 * right edge, one line below. What the action opens stands below the value column.
 */
export default function ValueRow({
  title,
  value,
  action,
  children,
  headingLevel = 'h2',
}: {
  title: string
  value: ReactNode
  /** One text action, e.g. „Übergeben“, „Ändern“, „Anzeigen“. */
  action?: ReactNode
  /** Opened content, under the value column. */
  children?: ReactNode
  headingLevel?: 'h2' | 'h3'
}) {
  const id = useId()
  return (
    <Box
      component="section"
      aria-labelledby={id}
      sx={{ borderBottom: 1, borderColor: 'divider', py: 2 }}
    >
      <Box sx={ROW_GRID}>
        <Typography
          id={id}
          component={headingLevel}
          sx={{ fontSize: 15, fontWeight: 600, color: 'text.primary', gridColumn: '1' }}
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
        {action && (
          <Box sx={{ gridColumn: { xs: '2', sm: '3' }, gridRow: '1', justifySelf: 'end' }}>
            {action}
          </Box>
        )}
        {children && (
          <Box sx={{ gridColumn: { xs: '1 / -1', sm: '2 / -1' }, pt: 1 }}>{children}</Box>
        )}
      </Box>
    </Box>
  )
}
