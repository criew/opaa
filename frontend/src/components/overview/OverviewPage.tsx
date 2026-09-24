import { Fragment, useMemo, useState, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import CircularProgress from '@mui/material/CircularProgress'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import CloseIcon from '@mui/icons-material/Close'
import SearchIcon from '@mui/icons-material/Search'
import ViewModuleIcon from '@mui/icons-material/ViewModule'
import ViewListIcon from '@mui/icons-material/ViewList'
import PageHeading from '../a11y/PageHeading'
import { blue, fontFamily } from '../../theme/tokens'

export type OverviewView = 'cards' | 'table'

export interface OverviewColumn {
  /** Stable key of the column; only used for React's list identity. */
  key: string
  label: string
}

export interface OverviewPageProps<T> {
  /** Document title, and the heading unless `heading` names one. */
  title: string
  /** Puts the figure into the heading itself, e.g. `(n) => '3 Spaces'` (#1914). */
  heading?: (count: number) => string
  /** The quiet figure beside a fixed heading; use instead of `heading`. */
  countLabel?: (count: number) => string
  /** Both omitted where an overview offers no creation at all. */
  createLabel?: string
  onCreate?: () => void
  /** Distinguishes the remembered view; one key per overview. */
  storageKey: string
  /** Used on the first visit only; a narrow viewport always starts with cards. */
  defaultView?: OverviewView
  items: T[]
  itemKey: (item: T) => string
  /** Everything the client-side search matches against - name, description, whatever fits. */
  searchText?: (item: T) => string
  /**
   * Hands the search to the caller, e.g. to a server query: `items` are then the result as it
   * stands, and the overview filters nothing itself. Omitted, the search runs over `searchText`.
   */
  search?: { value: string; onChange: (value: string) => void }
  /** Further controls beside the search, e.g. a type filter. */
  filters?: ReactNode
  /** Whether `filters` currently narrow `items` - an empty result then reads as "no match". */
  filtered?: boolean
  /** The size of the whole result where `items` hold only a part of it, e.g. one page. */
  total?: number
  searchPlaceholder?: string
  isLoading?: boolean
  error?: string | null
  /** Shown instead of the list when the overview holds no items at all. */
  emptyState?: ReactNode
  columns: OverviewColumn[]
  renderCard: (item: T) => ReactNode
  /** The row's cells; the component supplies the surrounding `<tr>`. */
  renderRow: (item: T) => ReactNode
  /** Quiet note below the list, e.g. which items the list cannot show. */
  footNote?: ReactNode
  /** Rendered below the list, e.g. a button that loads the next page. */
  listFooter?: ReactNode
}

/** MUI's `md` breakpoint - the width from which the table view is the better first impression. */
const NARROW_QUERY = '(min-width: 900px)'

function viewStorageKey(storageKey: string): string {
  return `opaa.overview.${storageKey}.view`
}

/**
 * The remembered choice wins; without one a narrow viewport starts with cards, because a
 * multi-column table would arrive scrolled sideways before the switch has been found.
 */
function readView(storageKey: string, fallback: OverviewView): OverviewView {
  let stored: string | null = null
  try {
    stored = window.localStorage.getItem(viewStorageKey(storageKey))
  } catch {
    // A browser with blocked storage must still render the overview.
  }
  if (stored === 'cards' || stored === 'table') return stored
  const narrow = typeof window.matchMedia === 'function' && !window.matchMedia(NARROW_QUERY).matches
  return narrow ? 'cards' : fallback
}

function searchResultMessage(count: number, query: string): string {
  if (!query) {
    if (count === 0) return 'Kein Eintrag passt zu den Filtern.'
    return count === 1
      ? '1 Eintrag passt zu den Filtern.'
      : `${count} Einträge passen zu den Filtern.`
  }
  if (count === 0) return `Kein Eintrag passt zu „${query}“.`
  return count === 1 ? `1 Eintrag passt zu „${query}“.` : `${count} Einträge passen zu „${query}“.`
}

function matches(text: string, query: string): boolean {
  const haystack = text.toLowerCase()
  return query
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean)
    .every((term) => haystack.includes(term))
}

/**
 * The shared frame of every asset overview (#1913): heading with count, "Neu" button, client-side
 * search and a card/table switch whose choice is remembered per overview. What an item looks like
 * stays with the caller - `renderCard` fills the card grid, `renderRow` the table body under
 * `columns`, and `searchText` decides what the search sees.
 */
export default function OverviewPage<T>({
  title,
  heading,
  countLabel,
  createLabel,
  onCreate,
  storageKey,
  defaultView = 'cards',
  items,
  itemKey,
  searchText,
  search,
  filters,
  filtered = false,
  total,
  searchPlaceholder = 'Name oder Beschreibung …',
  isLoading = false,
  error = null,
  emptyState,
  columns,
  renderCard,
  renderRow,
  footNote,
  listFooter,
}: OverviewPageProps<T>) {
  const [view, setView] = useState<OverviewView>(() => readView(storageKey, defaultView))
  const [ownQuery, setOwnQuery] = useState('')
  const query = search ? search.value : ownQuery
  const setQuery = search ? search.onChange : setOwnQuery

  const visible = useMemo(() => {
    const trimmed = query.trim()
    if (search || !trimmed || !searchText) return items
    return items.filter((item) => matches(searchText(item), trimmed))
  }, [items, query, search, searchText])

  function changeView(next: OverviewView | null) {
    if (!next) return
    setView(next)
    try {
      window.localStorage.setItem(viewStorageKey(storageKey), next)
    } catch {
      // Remembering the view is a convenience; a blocked storage must not break the switch.
    }
  }

  // A narrowed result is still "a list with no match", never the overview's empty state - the
  // search and the filters have to stay in reach to widen it again.
  const narrowed = filtered || (search !== undefined && query.trim() !== '')
  const isEmpty = items.length === 0 && !narrowed
  const firstLoad = isLoading && items.length === 0 && !narrowed
  const showList = visible.length > 0
  const count = total ?? visible.length

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2, mb: 2.5, flexWrap: 'wrap' }}>
        <PageHeading title={firstLoad || !heading ? title : heading(count)} documentTitle={title} />
        {countLabel && !firstLoad && (
          <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
            {countLabel(count)}
          </Typography>
        )}
        {createLabel && onCreate && (
          <Button variant="contained" onClick={onCreate} sx={{ ml: 'auto', flex: 'none' }}>
            {createLabel}
          </Button>
        )}
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {!isEmpty && (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1.5,
            mb: 2.5,
            flexWrap: 'wrap',
          }}
        >
          <TextField
            size="small"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={searchPlaceholder}
            sx={{ flex: '1 1 260px', maxWidth: 420 }}
            slotProps={{
              input: {
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchIcon sx={{ fontSize: 16 }} />
                  </InputAdornment>
                ),
                endAdornment: query ? (
                  <InputAdornment position="end">
                    <IconButton
                      size="small"
                      aria-label="Suche zurücksetzen"
                      onClick={() => setQuery('')}
                    >
                      <CloseIcon sx={{ fontSize: 16 }} />
                    </IconButton>
                  </InputAdornment>
                ) : undefined,
              },
              htmlInput: { 'aria-label': 'Suchen' },
            }}
          />
          {filters}
          <ToggleButtonGroup
            size="small"
            exclusive
            value={view}
            onChange={(_event, next: OverviewView | null) => changeView(next)}
            aria-label="Ansicht"
            sx={{ ml: 'auto', flex: 'none' }}
          >
            <ToggleButton value="cards" aria-label="Kacheln">
              <ViewModuleIcon sx={{ fontSize: 18 }} />
            </ToggleButton>
            <ToggleButton value="table" aria-label="Tabelle">
              <ViewListIcon sx={{ fontSize: 18 }} />
            </ToggleButton>
          </ToggleButtonGroup>
        </Box>
      )}

      {/* Das Filtern verschiebt den Fokus nicht; ohne Live-Bereich bliebe das Ergebnis am
          Screenreader unbemerkt (accessibility.md, Prüfpunkt 2.8). */}
      <Box role="status" aria-live="polite" sx={visuallyHidden}>
        {query.trim() || filtered ? searchResultMessage(count, query.trim()) : ''}
      </Box>

      {firstLoad ? (
        <Box sx={{ py: 6, display: 'flex', justifyContent: 'center' }}>
          <CircularProgress size={24} aria-label="Liste wird geladen" />
        </Box>
      ) : isEmpty ? (
        emptyState
      ) : visible.length === 0 && !isLoading ? (
        <Typography sx={{ color: 'text.secondary' }}>
          {searchResultMessage(0, query.trim())}
        </Typography>
      ) : null}

      {showList && view === 'cards' && (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
            gap: 2.25,
          }}
        >
          {visible.map((item) => (
            <Fragment key={itemKey(item)}>{renderCard(item)}</Fragment>
          ))}
        </Box>
      )}

      {showList && visible.length > 0 && view === 'table' && (
        <Box sx={{ overflowX: 'auto' }}>
          <Table
            size="small"
            sx={{
              '& th': { fontFamily: fontFamily.mono, fontSize: 10, letterSpacing: '0.08em' },
              '& td': { fontSize: 13.5, py: 1.375 },
            }}
          >
            <TableHead>
              <TableRow>
                {columns.map((column) => (
                  <TableCell key={column.key}>{column.label}</TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {visible.map((item) => (
                <TableRow key={itemKey(item)} sx={{ position: 'relative' }}>
                  {renderRow(item)}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      )}

      {showList && listFooter}

      {showList && footNote && (
        <Typography sx={{ fontSize: 11.5, color: 'text.secondary', mt: 1.5 }}>
          {footNote}
        </Typography>
      )}
    </Box>
  )
}

/**
 * The card shell of an overview: a real link (new tab, middle click, history) with the quiet
 * border and hover lift of mockup 1c. Motion stays on transform only (guidelines 4.5).
 */
export function OverviewCard({ to, children }: { to: string; children: ReactNode }) {
  return (
    <ButtonBase
      component={RouterLink}
      to={to}
      sx={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'stretch',
        textAlign: 'left',
        gap: 1,
        p: 2.5,
        border: 1,
        borderColor: 'divider',
        borderRadius: '16px',
        bgcolor: 'background.paper',
        transition: (theme) =>
          theme.transitions.create(['border-color', 'transform'], {
            duration: theme.transitions.duration.shortest,
          }),
        '&:hover': {
          borderColor: blue[300],
          transform: 'translateY(-2px)',
        },
      }}
    >
      {children}
    </ButtonBase>
  )
}

/**
 * The name link of a table row: its stretched pseudo-element turns the whole row into one click
 * target while the link stays its single tab stop (guidelines 5.3). Relies on the row being
 * positioned, which `OverviewPage` guarantees for every row it renders.
 */
export function OverviewRowLink({ to, children }: { to: string; children: ReactNode }) {
  return (
    <Typography
      component={RouterLink}
      to={to}
      sx={{
        fontSize: 13.5,
        fontWeight: 500,
        color: 'text.primary',
        textDecoration: 'none',
        '&::after': { content: '""', position: 'absolute', inset: 0 },
      }}
    >
      {children}
    </Typography>
  )
}
