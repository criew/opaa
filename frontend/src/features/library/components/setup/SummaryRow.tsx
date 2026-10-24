import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'

interface SummaryRowProps {
  id: string
  title: string
  /** What is set, in one line; null while nothing is. */
  summary: string | null
  /** Shown instead of a missing summary. */
  emptyText?: string
  /** Issues of the row, shown under the summary while it is closed. */
  issues?: string[]
  open: boolean
  /** Absent for a reader: the row then only shows what is set. */
  onOpen?: () => void
  headingLevel?: 'h2' | 'h3'
  children?: ReactNode
}

/**
 * One quiet row of a disclosure list: title, summary and „Ändern“, which opens the row's fields in
 * place. Which row is open is the caller's state - the setup keeps at most one open.
 */
export default function SummaryRow({
  id,
  title,
  summary,
  emptyText = 'Noch nicht festgelegt',
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
      sx={{ borderTop: 1, borderColor: 'divider', '&:last-of-type': { borderBottom: 1, borderColor: 'divider' } }}
    >
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr auto', sm: '128px 1fr auto' },
          columnGap: 2,
          rowGap: 0.5,
          alignItems: 'baseline',
          py: 2,
        }}
      >
        <Typography
          component={headingLevel}
          id={`${id}-title`}
          sx={{ fontSize: 15, fontWeight: 600, gridColumn: '1' }}
        >
          {title}
        </Typography>
        <Box
          sx={{
            gridColumn: { xs: '1 / -1', sm: '2' },
            gridRow: { xs: '2', sm: '1' },
            minWidth: 0,
          }}
        >
          {!open && (
            <Typography
              sx={{
                fontSize: 13.5,
                color: summary ? 'text.primary' : 'text.secondary',
                overflowWrap: 'anywhere',
              }}
            >
              {summary ?? emptyText}
            </Typography>
          )}
          {!open && issues.length > 0 && (
            <Box id={issuesId} sx={{ mt: 0.5 }}>
              {issues.map((issue) => (
                <Typography key={issue} sx={{ fontSize: 12.5, color: 'error.main' }}>
                  {issue}
                </Typography>
              ))}
            </Box>
          )}
        </Box>
        {onOpen && !open && (
          <Button
            variant="text"
            size="small"
            onClick={onOpen}
            aria-expanded={false}
            aria-controls={bodyId}
            aria-describedby={issues.length > 0 ? issuesId : undefined}
            sx={{ gridColumn: { xs: '2', sm: '3' }, gridRow: '1', minWidth: 0, alignSelf: 'center' }}
          >
            Ändern
            <Box component="span" sx={visuallyHidden}>
              {` ${title}`}
            </Box>
          </Button>
        )}
      </Box>
      {open && (
        <Box id={bodyId} sx={{ pb: 3, maxWidth: 640 }}>
          {children}
        </Box>
      )}
    </Box>
  )
}
