import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import TableCell from '@mui/material/TableCell'
import TableSortLabel from '@mui/material/TableSortLabel'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined'
import { radius } from '../../../theme/tokens'

/**
 * Die gemeinsamen Bausteine der Listen der Administration (Konten, Gruppen, #1978): Tabelle mit
 * festen Spaltenbreiten, sortierbare Köpfe, Seitenumschalter, Leer- und Ladezustand, Zustandszelle.
 * Beide Listen setzen sich daraus zusammen, damit sie gleich aussehen und sich gleich bedienen.
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
export function SortableHeadCell<F extends string>({
  fields,
  binding,
  width,
  align,
}: {
  fields: F[]
  binding: SortBinding<F>
  width?: string
  align?: 'left' | 'right'
}) {
  const active = fields.includes(binding.sort)
  return (
    <TableCell
      align={align}
      sortDirection={active ? binding.direction : false}
      sx={width ? { width } : undefined}
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

/** Page switch with German labels; `countLabel` names the total, e.g. „3 Konten". */
export function ListPager({
  total,
  size,
  page,
  onPage,
  countLabel,
}: {
  total: number
  size: number
  page: number
  onPage: (page: number) => void
  countLabel: string
}) {
  const pageCount = Math.max(1, Math.ceil(total / Math.max(size, 1)))
  if (total === 0) return null
  return (
    <Stack
      direction="row"
      spacing={1}
      sx={{ alignItems: 'center', justifyContent: 'flex-end', mt: 1.5, flexWrap: 'wrap' }}
    >
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
        {countLabel} · Seite {page + 1} von {pageCount}
      </Typography>
      <Tooltip title="Vorherige Seite">
        <span>
          <IconButton
            size="small"
            aria-label="Vorherige Seite"
            disabled={page === 0}
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
            disabled={page + 1 >= pageCount}
            onClick={() => onPage(page + 1)}
          >
            <ChevronRightIcon fontSize="small" />
          </IconButton>
        </span>
      </Tooltip>
    </Stack>
  )
}

/** The dashed box a list shows when no row matches the chosen filters. */
export function ListEmptyState({ title, hint }: { title: string; hint: string }) {
  return (
    <Box
      sx={{
        border: 1,
        borderStyle: 'dashed',
        borderColor: 'divider',
        borderRadius: `${radius.md}px`,
        p: 3,
        textAlign: 'center',
      }}
    >
      <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>{title}</Typography>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>{hint}</Typography>
    </Box>
  )
}

/** Three placeholder rows while the first page loads. */
export function ListLoading({ label }: { label: string }) {
  return (
    <Stack spacing={1} aria-busy="true">
      <span style={visuallyHidden}>{label}</span>
      <Skeleton variant="rounded" height={48} />
      <Skeleton variant="rounded" height={48} />
      <Skeleton variant="rounded" height={48} />
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
          '&:focus-visible': { outline: 2, outlineColor: 'primary.main', outlineOffset: 1 },
        }}
      >
        <InfoOutlinedIcon aria-hidden sx={{ fontSize: 15 }} />
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
      <Typography component="span" sx={{ fontSize: 13, fontWeight: 500 }}>
        {label}
      </Typography>
      {hint && <InfoHint label={hint.label} reason={hint.reason} />}
    </Stack>
  )
}
