import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import StateDot from '@/shared/ui/StateDot'
import PathLabel from './PathLabel'
import { displayPath, PATH_SEPARATOR } from './paths'
import { ROW_GRID, TEXT_LINK } from './rowStyles'
import type { RowStatus, RowSummary } from './setupTypes'

/** Entries a list summary names before „und n weitere“. */
const SHOWN = 5

/** Compares entries regardless of spacing around separators and a trailing slash. */
function entryKey(entry: string): string {
  return entry.toLowerCase().replace(/\s+/g, '').replace(/\/+$/, '')
}

/** A path or address in the one display form: host and segments joined by the separator. */
function placePath(place: string): string {
  if (/^https?:\/\//i.test(place)) {
    try {
      const url = new URL(place)
      return [url.host, ...url.pathname.split('/').filter(Boolean)].join(PATH_SEPARATOR)
    } catch {
      return place
    }
  }
  return displayPath(place)
}

/** „· nicht lesbar“ after an entry: the state in words, the signal colour only in the dot. */
export function UnreadableMark() {
  return (
    <Box component="span" sx={{ color: 'text.secondary', whiteSpace: 'nowrap', ml: 0.75 }}>
      {'· '}
      <Box
        component="span"
        aria-hidden
        sx={{
          display: 'inline-block',
          width: 7,
          height: 7,
          borderRadius: '50%',
          bgcolor: 'warning.main',
          mr: 0.5,
          verticalAlign: 'middle',
          position: 'relative',
          top: -1,
        }}
      />
      nicht lesbar
    </Box>
  )
}

/** A row's value: one line, or a lead line („12 Spaces“) with up to five entries below. */
export function SummaryValue({
  summary,
  status,
  emptyText = 'keine',
  marked = [],
}: {
  summary: RowSummary
  status?: RowStatus | null
  emptyText?: string
  /** Entries to mark as not readable, right at the entry. */
  marked?: string[]
}) {
  const text = typeof summary === 'string' ? summary : (summary?.lead ?? null)
  const markedKeys = new Set(marked.map(entryKey))
  const isMarked = (item: string) => markedKeys.has(entryKey(item))
  // unreadable entries first, so they are among the five shown
  const listed = summary && typeof summary !== 'string' ? summary.items : []
  const items = [...listed.filter(isMarked), ...listed.filter((item) => !isMarked(item))]
  const unlisted = marked.filter(
    (name) => !listed.some((item) => entryKey(item) === entryKey(name)),
  )
  return (
    <Box sx={{ minWidth: 0 }}>
      {/* target and state side by side, the state after a gap instead of a dot, so a wrapped
          line never ends on a separator; a long target is cut with its full text as tooltip */}
      <Box
        sx={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'baseline',
          columnGap: 1.5,
          minWidth: 0,
        }}
      >
        <Typography
          title={text ?? undefined}
          sx={{
            fontSize: 13.5,
            color: text ? 'text.primary' : 'text.secondary',
            minWidth: 0,
            maxWidth: '100%',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
        >
          {text ?? emptyText}
        </Typography>
        {status && <StateDot tone={status.tone} label={status.label} />}
      </Box>
      {items.length > 0 && (
        <Box component="ul" sx={{ m: 0, mt: 0.25, p: 0, listStyle: 'none' }}>
          {items.slice(0, SHOWN).map((item) => (
            <Box
              component="li"
              key={item}
              sx={{ fontSize: 13.5, lineHeight: '22px', display: 'flex', alignItems: 'baseline' }}
            >
              <Box component="span" sx={{ minWidth: 0, flex: '0 1 auto' }}>
                <PathLabel path={item} />
              </Box>
              {isMarked(item) && <UnreadableMark />}
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
      {/* entries the summary does not list itself, such as a path below a whole directory: in a
          block of their own, the path cut in the middle, the mark never */}
      {unlisted.length > 0 && (
        <Box component="ul" sx={{ m: 0, mt: 0.75, p: 0, listStyle: 'none' }}>
          {unlisted.slice(0, SHOWN).map((name) => (
            <Box
              component="li"
              key={`unlisted-${name}`}
              sx={{ fontSize: 13.5, lineHeight: '22px', display: 'flex', alignItems: 'baseline' }}
            >
              <Box component="span" sx={{ minWidth: 0, flex: '0 1 auto' }}>
                <PathLabel path={placePath(name)} />
              </Box>
              <UnreadableMark />
            </Box>
          ))}
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
  /** A line of state under the summary while closed, e.g. entries the last run could not read. */
  note?: ReactNode
  /** List entries marked as not readable at the entry itself. */
  marked?: string[]
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
  note,
  marked,
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
            <SummaryValue summary={summary} status={status} marked={marked} />
            {note && <Box sx={{ mt: 0.5 }}>{note}</Box>}
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
