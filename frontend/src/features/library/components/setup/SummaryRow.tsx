import { useState, type ReactNode } from 'react'
import Dialog from '@mui/material/Dialog'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import TextField from '@mui/material/TextField'
import { TextAction } from '@/shared/ui/DetailSection'
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
const PART_GUTTER = 16

/**
 * The parts of a summary („täglich um 06:00 · nächster Lauf …“), each kept as a unit: a part
 * that does not fit starts a new line, and the separator before it sits in a clipped gutter -
 * no line starts or ends on „·“.
 */
function SummaryParts({ text }: { text: string }) {
  const parts = text.split(' · ')
  if (parts.length === 1) return text
  return (
    <Box component="span" sx={{ display: 'block', overflow: 'hidden' }}>
      <Box component="span" sx={{ display: 'flex', flexWrap: 'wrap', ml: `-${PART_GUTTER}px` }}>
        {parts.map((part, index) => (
          <Box
            component="span"
            key={index}
            sx={{
              minWidth: 0,
              '&::before': {
                content: '"·"',
                display: 'inline-block',
                width: PART_GUTTER,
                textAlign: 'center',
                color: 'text.secondary',
              },
            }}
          >
            {part}
          </Box>
        ))}
      </Box>
    </Box>
  )
}

export function SummaryValue({
  summary,
  status,
  emptyText = 'keine',
  statusBelow = false,
  markedItems,
  expandable = false,
}: {
  summary: RowSummary
  status?: RowStatus | null
  emptyText?: string
  /** The state on a line of its own under the value, never trailing it („…ablage ·“). */
  statusBelow?: boolean
  /** Entries to mark as not readable, matched against the listed entries. */
  markedItems?: string[]
  /** „und n weitere“ becomes „alle n anzeigen“ with a searchable list. */
  expandable?: boolean
}) {
  const [showAll, setShowAll] = useState(false)
  const [query, setQuery] = useState('')
  const text = typeof summary === 'string' ? summary : (summary?.lead ?? null)
  const items = summary && typeof summary !== 'string' ? summary.items : []
  const norm = (value: string) => value.replace(/\s+/g, '').replace(/\/+$/, '').toLowerCase()
  const marks = (markedItems ?? []).map(norm).filter(Boolean)
  const isMarked = (item: string) => {
    const value = norm(item)
    return marks.some(
      (mark) => value === mark || value.endsWith(`/${mark}`) || value.startsWith(mark),
    )
  }
  const mark = (item: string) =>
    isMarked(item) ? (
      <Box component="span" sx={{ ml: 1, display: 'inline-flex' }}>
        <StateDot tone="warning" label="nicht lesbar" />
      </Box>
    ) : null
  if (statusBelow) {
    return (
      <Box sx={{ minWidth: 0 }}>
        <Typography
          sx={{
            fontSize: 13.5,
            color: text ? 'text.primary' : 'text.secondary',
            overflowWrap: 'anywhere',
          }}
        >
          {text ? <SummaryParts text={text} /> : emptyText}
        </Typography>
        {/* the collective mark only where no marked entry is in sight */}
        {status && !status.entries && !(markedItems && items.slice(0, SHOWN).some(isMarked)) && (
          <StateDot tone={status.tone} label={status.label} />
        )}
        {items.length > 0 && (
          <Box component="ul" sx={{ m: 0, mt: 0.25, p: 0, listStyle: 'none' }}>
            {items.slice(0, SHOWN).map((item) => (
              <Box
                component="li"
                key={item}
                sx={{ fontSize: 13.5, lineHeight: '22px', display: 'flex' }}
              >
                <Box component="span" sx={{ minWidth: 0 }}>
                  <PathLabel path={item} />
                </Box>
                {mark(item)}
              </Box>
            ))}
            {items.length > SHOWN && (
              <Box
                component="li"
                sx={{ fontSize: 13.5, lineHeight: '22px', color: 'text.secondary' }}
              >
                {expandable ? (
                  <TextAction onClick={() => setShowAll(true)}>
                    alle {items.length} anzeigen
                  </TextAction>
                ) : (
                  `und ${items.length - SHOWN} weitere`
                )}
              </Box>
            )}
          </Box>
        )}
        {status?.entries && (
          <Box component="ul" sx={{ m: 0, mt: 0.25, p: 0, listStyle: 'none' }}>
            {status.entries.map((entry) => (
              <Box
                component="li"
                key={entry}
                sx={{ fontSize: 13.5, lineHeight: '22px', display: 'flex' }}
              >
                <Box component="span" sx={{ minWidth: 0 }}>
                  <PathLabel path={entry} />
                </Box>
                <Box component="span" sx={{ ml: 1, display: 'inline-flex' }}>
                  <StateDot tone={status.tone} label={status.label} />
                </Box>
              </Box>
            ))}
          </Box>
        )}
        {expandable && (
          <Dialog open={showAll} onClose={() => setShowAll(false)} fullWidth maxWidth="sm">
            <DialogTitle>{text}</DialogTitle>
            <DialogContent>
              <TextField
                size="small"
                fullWidth
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Name enthält …"
                slotProps={{ htmlInput: { 'aria-label': 'Einträge durchsuchen' } }}
                sx={{ mb: 1.5, mt: 0.5 }}
              />
              <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
                {items
                  .filter(
                    (item) =>
                      !query.trim() || item.toLowerCase().includes(query.trim().toLowerCase()),
                  )
                  .map((item) => (
                    <Box
                      component="li"
                      key={item}
                      sx={{
                        py: 0.75,
                        fontSize: 13.5,
                        borderBottom: 1,
                        borderColor: 'divider',
                        display: 'flex',
                      }}
                    >
                      <Box component="span" sx={{ minWidth: 0 }}>
                        <PathLabel path={item} />
                      </Box>
                      {mark(item)}
                    </Box>
                  ))}
              </Box>
            </DialogContent>
          </Dialog>
        )}
      </Box>
    )
  }
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
  /** The Reiter „Quelle“: state on its own line, marked entries, the whole list on request. */
  detail?: { markedItems?: string[] }
  /** The word of the link that opens the row; „Ändern“ unless the row has nothing set yet. */
  actionLabel?: string
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
  actionLabel = 'Ändern',
  detail,
  children,
}: SummaryRowProps) {
  const bodyId = `${id}-body`
  const issuesId = `${id}-issues`
  return (
    <Box
      component="section"
      aria-labelledby={`${id}-title`}
      data-row={id}
      // an anchor below the sticky header of the narrow layout
      sx={{ borderBottom: 1, borderColor: 'divider', py: 2, scrollMarginTop: { xs: 72, sm: 16 } }}
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
            <SummaryValue
              summary={summary}
              status={status}
              statusBelow={Boolean(detail)}
              markedItems={detail?.markedItems}
              expandable={Boolean(detail)}
            />
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
            {actionLabel}
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
