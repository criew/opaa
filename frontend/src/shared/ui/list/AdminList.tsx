import { Fragment, type MouseEvent, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TableSortLabel from '@mui/material/TableSortLabel'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme, type Theme } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined'
import { fontFamily, fontSize, iconSize } from '../../theme/tokens'
import { adminTableSx, definitionListSx, listCardSx } from './adminListStyles'

/**
 * The one building block of every table in the application (guidelines 5.3): `AdminList` renders
 * the table from the `md` breakpoint and the card list below it, with head, row link, row action
 * and pager. Loading, empty and error states come from `shared/ui/states`. No theme imports
 * MUI's `Table` itself (ESLint).
 */

export type SortDirection = 'asc' | 'desc'

/** The sort state of a list and how a click on a column head changes it. */
export interface SortBinding<F extends string> {
  sort: F
  direction: SortDirection
  onSort: (sort: F, direction: SortDirection) => void
  /** The column head's own word. */
  labels: Record<F, string>
  /** What the screen reader announces after a click - for ranked columns the order itself. */
  descriptions: Record<F, string>
}

function SortableLabel<F extends string>({
  field,
  binding,
}: {
  field: F
  binding: SortBinding<F>
}) {
  const active = binding.sort === field
  return (
    <TableSortLabel
      active={active}
      direction={active ? binding.direction : 'asc'}
      onClick={() => binding.onSort(field, active && binding.direction === 'asc' ? 'desc' : 'asc')}
    >
      {binding.labels[field]}
      {active && (
        <span style={visuallyHidden}>
          {binding.direction === 'asc'
            ? `aufsteigend nach ${binding.descriptions[field]} sortiert`
            : `absteigend nach ${binding.descriptions[field]} sortiert`}
        </span>
      )}
    </TableSortLabel>
  )
}

/** A header cell with one or several sort controls - several where a column carries two values. */
function SortableHeadCell<F extends string>({
  fields,
  binding,
  width,
  align,
}: {
  fields: F[]
  binding: SortBinding<F>
  width?: string | number
  align?: 'left' | 'right'
}) {
  const active = fields.includes(binding.sort)
  return (
    <TableCell
      align={align}
      sortDirection={active ? binding.direction : false}
      sx={width !== undefined ? { width } : undefined}
    >
      <Stack
        direction="row"
        spacing={0.5}
        sx={{ alignItems: 'center', justifyContent: align === 'right' ? 'flex-end' : undefined }}
      >
        {fields.map((field, index) => (
          <Box key={field} component="span" sx={{ display: 'inline-flex', alignItems: 'center' }}>
            {index > 0 && (
              <Box component="span" aria-hidden="true" sx={{ mr: 0.5, color: 'text.disabled' }}>
                ·
              </Box>
            )}
            <SortableLabel field={field} binding={binding} />
          </Box>
        ))}
      </Stack>
    </TableCell>
  )
}

/**
 * A list paged by a known total or page count, or by a server that only says whether more
 * follows.
 */
export type PagerState = {
  page: number
  onPage: (page: number) => void
  disabled?: boolean
  /** Tells two pagers on one page apart; „Seiten“ otherwise. */
  label?: string
} & ({ total: number; size: number } | { pageCount: number } | { hasMore: boolean })

/**
 * The one form of paging (guidelines 5.3): „Seite 2 von 5" with arrows, bottom right - „Seite 2"
 * where the source knows no total. A list that fits on one page shows no pager; the count of the
 * list stands next to the page title.
 */
export function ListPager(props: PagerState) {
  const { page, onPage, disabled = false, label = 'Seiten' } = props
  const pageCount =
    'total' in props
      ? Math.max(1, Math.ceil(props.total / Math.max(props.size, 1)))
      : 'pageCount' in props
        ? Math.max(1, props.pageCount, page + 1)
        : null
  const hasNext =
    pageCount !== null ? page + 1 < pageCount : (props as { hasMore: boolean }).hasMore
  if (page === 0 && !hasNext) return null
  return (
    <Stack
      component="nav"
      aria-label={label}
      direction="row"
      spacing={1}
      sx={{ alignItems: 'center', justifyContent: 'flex-end', mt: 1.5 }}
    >
      <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
        Seite {page + 1}
        {pageCount !== null && ` von ${pageCount}`}
      </Typography>
      <Tooltip title="Vorherige Seite">
        <span>
          <IconButton
            size="small"
            aria-label="Vorherige Seite"
            disabled={disabled || page === 0}
            onClick={() => onPage(page - 1)}
          >
            <ChevronLeftIcon fontSize="small" />
          </IconButton>
        </span>
      </Tooltip>
      <Tooltip title="Nächste Seite">
        <span>
          <IconButton
            size="small"
            aria-label="Nächste Seite"
            disabled={disabled || !hasNext}
            onClick={() => onPage(page + 1)}
          >
            <ChevronRightIcon fontSize="small" />
          </IconButton>
        </span>
      </Tooltip>
    </Stack>
  )
}

/**
 * A reason behind an info symbol, so a state stays one word on one line. The symbol is focusable
 * and carries the reason as its name: the tooltip opens on hover and on keyboard focus, and a
 * screen reader announces the reason without it.
 */
export function InfoHint({ label, reason }: { label: string; reason: string }) {
  return (
    <Tooltip title={reason}>
      <Box
        component="span"
        role="img"
        tabIndex={0}
        aria-label={`${label}: ${reason}`}
        sx={{
          display: 'inline-flex',
          color: 'text.secondary',
          borderRadius: '50%',
          cursor: 'help',
        }}
      >
        <InfoOutlinedIcon aria-hidden sx={{ fontSize: iconSize.sm }} />
      </Box>
    </Tooltip>
  )
}

/**
 * A state as a coloured dot next to its word - meaning-only colour, never a coloured chip
 * (guidelines 1.2, 5.5) - with an optional reason behind an info symbol.
 */
export function StateLabel({
  color,
  label,
  hint,
}: {
  color: string
  label: string
  hint?: { label: string; reason: string } | null
}): ReactNode {
  return (
    <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
      <Box
        component="span"
        aria-hidden="true"
        sx={{ width: 8, height: 8, borderRadius: '50%', flex: 'none', bgcolor: color }}
      />
      <Typography component="span" sx={{ fontSize: fontSize.text, fontWeight: 500 }}>
        {label}
      </Typography>
      {hint && <InfoHint label={hint.label} reason={hint.reason} />}
    </Stack>
  )
}

const ellipsisSx = { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } as const

/**
 * A text of a cell on one line: cut off with an ellipsis, the full value in the tooltip
 * (guidelines 5.3, checklist P22). `strong` for the name that carries the row, `secondary` for
 * the line below it, `mono` for an identifier.
 */
export function ListText({
  children,
  strong = false,
  secondary = false,
  mono = false,
}: {
  children: string | number | null | undefined
  strong?: boolean
  secondary?: boolean
  mono?: boolean
}) {
  if (children === null || children === undefined || children === '') return null
  return (
    <Typography
      title={String(children)}
      sx={{
        ...ellipsisSx,
        display: 'block',
        minWidth: 0,
        fontSize: secondary || mono ? fontSize.meta : fontSize.text,
        ...(strong && { fontWeight: 600 }),
        ...(secondary && { color: 'text.secondary' }),
        ...(mono && { fontFamily: fontFamily.mono }),
      }}
    >
      {children}
    </Typography>
  )
}

/**
 * The one visible action of a row (guidelines 5.3): compact, secondary, a verb. Never primary,
 * never danger text; everything further and everything destructive goes behind „⋯".
 */
export function RowAction({
  children,
  label,
  onClick,
  disabled = false,
  loading = false,
}: {
  /** The verb. */
  children: string
  /** The accessible name where the verb alone does not tell rows apart. */
  label?: string
  onClick: () => void
  disabled?: boolean
  loading?: boolean
}) {
  return (
    <Button
      size="small"
      variant="outlined"
      aria-label={label}
      disabled={disabled}
      loading={loading}
      onClick={onClick}
      sx={{ whiteSpace: 'nowrap' }}
    >
      {children}
    </Button>
  )
}

/**
 * Where a row leads: a route, or a panel or dialog the page opens. With `href` the page still
 * opens the target itself, and the row is a real link that a new tab can follow. With `expanded`
 * the row opens its own detail below itself (`rowDetail`) and says so to assistive technology.
 */
export type RowTarget = { to: string } | { onOpen: () => void; href?: string; expanded?: boolean }

/** The tone of a row that needs attention. */
export type RowTone = 'success' | 'warning' | 'error'

export interface AdminListColumn<Row, F extends string = never> {
  id: string
  /** The column head, one or two words; in the card list the term of the value. */
  head: string
  /** What a short head stands for, read out after it and not shown. */
  headNote?: string
  /** The sort fields this head offers; needs `sort` on the list. */
  sortFields?: F[]
  width?: string | number
  align?: 'left' | 'right'
  cell: (row: Row, index: number) => ReactNode
  /** Prose - a message, a result - may wrap; names, dates and labels never do. */
  wrap?: boolean
  /**
   * The place in the card below the `md` breakpoint: `title` heads the card (default for the
   * first column), `line` stands below the title without a term, `fact` is a term with its
   * value (default), `hidden` stays in the table only.
   */
  card?: 'title' | 'line' | 'fact' | 'hidden'
  /**
   * For the title column of a row with a target: what stands below the name outside the row's
   * link, e.g. state badges. The link is named by the entry alone, so a state inside it would
   * not be announced.
   */
  below?: (row: Row, index: number) => ReactNode
}

const INTERACTIVE =
  'a, button, input, select, textarea, label, [role="button"], [role="checkbox"], [tabindex="0"]'

/**
 * A click anywhere on the row follows the row's link. Not counted: a control inside the row, the
 * row's open details, and an element of a menu or dialog that only bubbles through React's tree
 * from a portal. A focusable ancestor of the row, such as the scroll region, is not a control.
 */
function followRowLink(event: MouseEvent<HTMLElement>) {
  const target = event.target as HTMLElement
  const row = event.currentTarget
  if (!row.contains(target)) return
  const control = target.closest(INTERACTIVE)
  if (control !== null && row.contains(control) && control !== row) return
  const detail = target.closest('[data-row-detail]')
  if (detail !== null && row.contains(detail)) return
  row.querySelector<HTMLElement>('[data-row-link]')?.click()
}

/**
 * The row's one tab stop: its title, as a link to the route or a button for the panel. It is
 * named by the entry alone, not by the badges and second lines of the title cell.
 */
function RowLink({
  target,
  label,
  children,
}: {
  target: RowTarget
  label: string
  children: ReactNode
}) {
  const sx = { display: 'block', minWidth: 0, color: 'inherit', borderRadius: 0.5 } as const
  if ('to' in target) {
    return (
      <Link
        component={RouterLink}
        to={target.to}
        underline="none"
        aria-label={label}
        data-row-link=""
        sx={sx}
      >
        {children}
      </Link>
    )
  }
  if (target.href !== undefined) {
    return (
      <Link
        href={target.href}
        underline="none"
        aria-label={label}
        data-row-link=""
        onClick={(event) => {
          // A modified click opens the address in a new tab or window, as on any link.
          if (event.metaKey || event.ctrlKey || event.shiftKey) return
          event.preventDefault()
          target.onOpen()
        }}
        sx={sx}
      >
        {children}
      </Link>
    )
  }
  return (
    <Box
      role="button"
      tabIndex={0}
      aria-label={label}
      data-row-link=""
      aria-expanded={target.expanded}
      onClick={target.onOpen}
      onKeyDown={(event) => {
        if (event.key !== 'Enter' && event.key !== ' ') return
        event.preventDefault()
        target.onOpen()
      }}
      sx={{ ...sx, cursor: 'pointer' }}
    >
      {children}
    </Box>
  )
}

/** A bare string or number is cut off on one line and names itself in the tooltip. */
function cellContent(value: ReactNode): ReactNode {
  if (typeof value === 'string' || typeof value === 'number') {
    return (
      <Box component="span" title={String(value)} sx={{ ...ellipsisSx, display: 'block' }}>
        {value}
      </Box>
    )
  }
  return value
}

export interface AdminListProps<Row, F extends string = never> {
  /** The accessible name of table and card list. */
  label: string
  rows: readonly Row[]
  rowKey: (row: Row) => string
  /** The name of one entry, for its card. */
  rowLabel: (row: Row) => string
  /** At most seven (guidelines 5.3); what goes beyond belongs into the row's detail. */
  columns: ReadonlyArray<AdminListColumn<Row, F>>
  sort?: SortBinding<F>
  /** Where the row leads: the whole row is then the link, with one tab stop on its title. */
  rowTarget?: (row: Row) => RowTarget | null
  /** The trailing cell: at most one `RowAction`, and the „⋯" menu. */
  rowActions?: (row: Row, index: number) => ReactNode
  /** Width of the trailing cell; 56 px carries a „⋯" alone. */
  actionsWidth?: number
  /** A leading cell without a head word, e.g. the selection box of a bulk action. */
  leading?: { head: string; width: number; cell: (row: Row) => ReactNode }
  isSelected?: (row: Row) => boolean
  /** Marks a row that needs attention with a bar in the tone of its state. */
  rowTone?: (row: Row) => RowTone | null
  /** Below this width the table scrolls sideways inside its region instead of squeezing. */
  minWidth?: number
  /**
   * What does not fit the seven columns: shown below the row across its width while it returns
   * something, e.g. while the row is expanded.
   */
  rowDetail?: (row: Row) => ReactNode
  /** Paging; shown only when there is more than one page. */
  pager?: PagerState
  /**
   * A scroll frame of fixed height with a sticky head, for a list inside a dialog: the dialog
   * keeps its size while the list is filtered. `emptyText` fills the frame without rows.
   */
  frame?: { height: number; headHeight: number; rowHeight: number; emptyText: string }
}

/**
 * A list of the administration: a table with fixed column widths and one-line cells from the
 * `md` breakpoint, a card list with the same words below it (guidelines 5.3).
 */
export default function AdminList<Row, F extends string = never>({
  label,
  rows,
  rowKey,
  rowLabel,
  columns,
  sort,
  rowTarget,
  rowActions,
  actionsWidth = 56,
  leading,
  isSelected,
  rowTone,
  minWidth,
  rowDetail,
  pager,
  frame,
}: AdminListProps<Row, F>) {
  const theme = useTheme()
  // Without `matchMedia` (tests, server rendering) the table is the form to assume.
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'), { defaultMatches: true })
  const rowSx = (row: Row, target: RowTarget | null) => {
    const tone = rowTone?.(row)
    return {
      ...(target && { cursor: 'pointer' }),
      ...(tone && { boxShadow: (t: Theme) => `inset 3px 0 0 ${t.palette[tone].main}` }),
    }
  }
  const titleColumn = columns.find((column) => column.card === 'title') ?? columns[0]
  const placeOf = (column: AdminListColumn<Row, F>) =>
    column === titleColumn ? 'title' : (column.card ?? 'fact')
  const pagerRow = pager ? <ListPager {...pager} /> : null
  const frameSx = frame && {
    height: frame.height,
    overflowY: 'auto',
    // Rules above and below mark the scroll region; a full frame is reserved for empty states.
    borderTop: 1,
    borderBottom: 1,
    borderColor: 'divider',
    '& thead tr': { height: frame.headHeight },
    '& tbody tr': { height: frame.rowHeight },
  }
  const frameEmpty = frame && rows.length === 0 && (
    <Typography
      sx={{
        fontSize: fontSize.text,
        color: 'text.secondary',
        px: 2,
        height: frame.rowHeight,
        display: 'flex',
        alignItems: 'center',
      }}
    >
      {frame.emptyText}
    </Typography>
  )

  if (!isDesktop) {
    const lines = columns.filter((column) => placeOf(column) === 'line')
    const facts = columns.filter((column) => placeOf(column) === 'fact')
    const cards = (
      <>
        <Box component="ul" aria-label={label} sx={{ listStyle: 'none', p: 0, m: 0 }}>
          {rows.map((row, index) => {
            const target = rowTarget?.(row) ?? null
            const title = titleColumn.cell(row, index)
            const detail = rowDetail?.(row)
            return (
              <Box
                component="li"
                key={rowKey(row)}
                onClick={target ? followRowLink : undefined}
                sx={{
                  ...listCardSx,
                  ...rowSx(row, target),
                  ...(rowTone?.(row) && { pl: 1.5 }),
                }}
              >
                <Box component="article" aria-label={rowLabel(row)}>
                  <>
                    <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
                      {leading && <Box sx={{ flex: 'none' }}>{leading.cell(row)}</Box>}
                      <Box sx={{ flex: 1, minWidth: 0 }}>
                        {target ? (
                          <RowLink target={target} label={rowLabel(row)}>
                            {title}
                          </RowLink>
                        ) : (
                          title
                        )}
                        {titleColumn.below?.(row, index)}
                        {lines.map((column) => {
                          const value = column.cell(row, index)
                          if (value === null || value === undefined || value === false) return null
                          return (
                            <Box key={column.id} sx={{ mt: 0.5, minWidth: 0 }}>
                              {value}
                            </Box>
                          )
                        })}
                      </Box>
                      {rowActions && (
                        <Stack
                          direction="row"
                          spacing={0.5}
                          sx={{ flex: 'none', alignItems: 'center' }}
                        >
                          {rowActions(row, index)}
                        </Stack>
                      )}
                    </Box>
                    {facts.length > 0 && (
                      <Box component="dl" sx={definitionListSx}>
                        {facts.map((column) => (
                          <Box key={column.id} sx={{ display: 'contents' }}>
                            <dt>{column.head}</dt>
                            <dd>{column.cell(row, index)}</dd>
                          </Box>
                        ))}
                      </Box>
                    )}
                    {detail && (
                      <Box data-row-detail="" sx={{ mt: 1.5, cursor: 'auto' }}>
                        {detail}
                      </Box>
                    )}
                  </>
                </Box>
              </Box>
            )
          })}
        </Box>
        {frameEmpty}
      </>
    )
    return (
      <>
        {frameSx ? (
          <Box role="region" tabIndex={0} aria-label={`${label}, scrollbar`} sx={frameSx}>
            {cards}
          </Box>
        ) : (
          cards
        )}
        {pagerRow}
      </>
    )
  }

  const table = (
    <Table
      size="small"
      stickyHeader={Boolean(frame)}
      aria-label={label}
      sx={{ ...adminTableSx, ...(minWidth && { minWidth }) }}
    >
      <TableHead>
        <TableRow>
          {leading && (
            <TableCell sx={{ width: leading.width, pl: 1.5, pr: 0 }}>
              <span style={visuallyHidden}>{leading.head}</span>
            </TableCell>
          )}
          {columns.map((column) =>
            column.sortFields && sort ? (
              <SortableHeadCell
                key={column.id}
                fields={column.sortFields}
                binding={sort}
                width={column.width}
                align={column.align}
              />
            ) : (
              <TableCell
                key={column.id}
                align={column.align}
                sx={column.width !== undefined ? { width: column.width } : undefined}
              >
                {column.head}
                {column.headNote && <span style={visuallyHidden}> {column.headNote}</span>}
              </TableCell>
            ),
          )}
          {rowActions && (
            <TableCell align="right" sx={{ width: actionsWidth }}>
              <span style={visuallyHidden}>Aktionen</span>
            </TableCell>
          )}
        </TableRow>
      </TableHead>
      <TableBody>
        {rows.map((row, index) => {
          const target = rowTarget?.(row) ?? null
          const detail = rowDetail?.(row)
          const hasDetail = detail !== null && detail !== undefined && detail !== false
          const columnCount = columns.length + (leading ? 1 : 0) + (rowActions ? 1 : 0)
          return (
            <Fragment key={rowKey(row)}>
              <TableRow
                hover={target !== null}
                selected={isSelected?.(row)}
                onClick={target ? followRowLink : undefined}
                sx={{
                  ...rowSx(row, target),
                  ...(hasDetail && { '& > td': { borderBottom: 0 } }),
                }}
              >
                <>
                  {leading && (
                    <TableCell sx={{ width: leading.width, pl: 1.5, pr: 0, py: 0.75 }}>
                      {leading.cell(row)}
                    </TableCell>
                  )}
                  {columns.map((column) => {
                    const value = column.cell(row, index)
                    const content = column.wrap ? value : cellContent(value)
                    return (
                      <TableCell
                        key={column.id}
                        align={column.align}
                        sx={{
                          ...(column.align === 'right' && { fontVariantNumeric: 'tabular-nums' }),
                          ...(column.wrap && { whiteSpace: 'normal', overflowWrap: 'anywhere' }),
                        }}
                      >
                        {target && column === titleColumn ? (
                          <RowLink target={target} label={rowLabel(row)}>
                            {content}
                          </RowLink>
                        ) : (
                          content
                        )}
                        {column === titleColumn && column.below?.(row, index)}
                      </TableCell>
                    )
                  })}
                  {rowActions && (
                    <TableCell align="right" sx={{ py: 0.5 }}>
                      <Stack
                        direction="row"
                        spacing={0.5}
                        sx={{ alignItems: 'center', justifyContent: 'flex-end' }}
                      >
                        {rowActions(row, index)}
                      </Stack>
                    </TableCell>
                  )}
                </>
              </TableRow>
              {hasDetail && (
                <TableRow>
                  <TableCell colSpan={columnCount} sx={{ whiteSpace: 'normal', pt: 0 }}>
                    {detail}
                  </TableCell>
                </TableRow>
              )}
            </Fragment>
          )
        })}
      </TableBody>
    </Table>
  )

  return (
    <>
      {frameSx ? (
        <TableContainer tabIndex={0} role="region" aria-label={`${label}, scrollbar`} sx={frameSx}>
          {table}
          {frameEmpty}
        </TableContainer>
      ) : minWidth ? (
        <TableContainer tabIndex={0} role="region" aria-label={`${label}, horizontal scrollbar`}>
          {table}
        </TableContainer>
      ) : (
        table
      )}
      {pagerRow}
    </>
  )
}
