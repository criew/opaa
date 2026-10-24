import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import StateDot from '@/shared/ui/StateDot'
import PathLabel from './PathLabel'
import { ROW_GRID, TEXT_LINK } from './rowStyles'
import type { RowStatus, RowSummary } from './setupTypes'

/** Entries a list summary names before „und n weitere“. */
const SHOWN = 5

/** A row's value: one line, or a lead line („12 Spaces“) with up to five entries below. */
export function SummaryValue({
  summary,
  status,
  emptyText = 'keine',
}: {
  summary: RowSummary
  status?: RowStatus | null
  emptyText?: string
}) {
  const text = typeof summary === 'string' ? summary : (summary?.lead ?? null)
  const items = summary && typeof summary !== 'string' ? summary.items : []
  return (
    <Box sx={{ minWidth: 0 }}>
      <Box
        sx={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'baseline',
          columnGap: 1,
        }}
      >
        <Typography
          sx={{
            fontSize: 13.5,
            color: text ? 'text.primary' : 'text.secondary',
            overflowWrap: 'anywhere',
            minWidth: 0,
          }}
        >
          {text ?? emptyText}
          {status && (
            <Box component="span" aria-hidden sx={{ display: { xs: 'none', sm: 'inline' } }}>
              {' ·'}
            </Box>
          )}
        </Typography>
        {status && (
          <Box sx={{ flexBasis: { xs: '100%', sm: 'auto' } }}>
            <StateDot tone={status.tone} label={status.label} />
          </Box>
        )}
      </Box>
      {items.length > 0 && (
        <Box component="ul" sx={{ m: 0, mt: 0.25, p: 0, listStyle: 'none' }}>
          {items.slice(0, SHOWN).map((item) => (
            <Box component="li" key={item} sx={{ fontSize: 13.5, lineHeight: '22px' }}>
              <PathLabel path={item} />
            </Box>
          ))}
          {items.length > SHOWN && (
            <Box
              component="li"
              sx={{ fontSize: 13.5, lineHeight: '22px', color: 'text.secondary' }}
            >
              und {items.length - SHOWN} weitere
            </Box>
          )}
        </Box>
      )}
    </Box>
  )
}

interface SummaryRowProps {
  id: string
  title: string
  summary: RowSummary
  status?: RowStatus | null
  /** Issues of the row, shown under the summary while it is closed. */
  issues?: string[]
  open: boolean
  /** Absent for a reader: the row then only shows what is set. */
  onOpen?: () => void
  headingLevel?: 'h2' | 'h3'
  children?: ReactNode
}

/**
 * One quiet row: title | summary | „Ändern“ as a text link at the shared right edge. „Ändern“
 * opens the row's fields below the summary column; the caller keeps at most one row open. Rows are
 * separated by one line below each; the first line is the tab bar's own edge.
 */
export default function SummaryRow({
  id,
  title,
  summary,
  status,
  issues = [],
  open,
  onOpen,
  headingLevel = 'h3',
  children,
}: SummaryRowProps) {
  const bodyId = `${id}-body`
  const issuesId = `${id}-issues`
  return (
    <Box
      component="section"
      aria-labelledby={`${id}-title`}
      data-row={id}
      sx={{ borderBottom: 1, borderColor: 'divider', py: 2 }}
    >
      <Box sx={ROW_GRID}>
        <Typography
          component={headingLevel}
          id={`${id}-title`}
          sx={{ fontSize: 15, fontWeight: 600, gridColumn: '1', color: 'text.primary' }}
        >
          {title}
        </Typography>
        {!open && (
          <Box sx={{ gridColumn: { xs: '1 / -1', sm: '2' }, gridRow: { xs: '2', sm: '1' } }}>
            <SummaryValue summary={summary} status={status} />
            {issues.length > 0 && (
              <Box id={issuesId} sx={{ mt: 0.5 }}>
                {issues.map((issue) => (
                  <Typography key={issue} sx={{ fontSize: 12.5, color: 'error.main' }}>
                    {issue}
                  </Typography>
                ))}
              </Box>
            )}
          </Box>
        )}
        {onOpen && !open && (
          <Link
            component="button"
            type="button"
            underline="hover"
            onClick={onOpen}
            aria-expanded={false}
            aria-controls={bodyId}
            aria-describedby={issues.length > 0 ? issuesId : undefined}
            sx={{
              ...TEXT_LINK,
              gridColumn: { xs: '2', sm: '3' },
              gridRow: '1',
              justifySelf: 'end',
            }}
          >
            Ändern
            <Box component="span" sx={visuallyHidden}>
              {` ${title}`}
            </Box>
          </Link>
        )}
        {open && (
          <Box
            id={bodyId}
            sx={{
              gridColumn: { xs: '1 / -1', sm: '2 / -1' },
              gridRow: { xs: '2', sm: '1' },
              pt: { xs: 1, sm: 0 },
            }}
          >
            {children}
          </Box>
        )}
      </Box>
    </Box>
  )
}
