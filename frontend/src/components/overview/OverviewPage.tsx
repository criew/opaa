import { Fragment, useMemo, useState, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import CircularProgress from '@mui/material/CircularProgress'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import CloseIcon from '@mui/icons-material/Close'
import SearchIcon from '@mui/icons-material/Search'
import PageHeading from '../a11y/PageHeading'
import type { Theme } from '@mui/material/styles'
import { blue } from '../../theme/tokens'

export interface ControlledSearch {
  value: string
  onChange: (value: string) => void
  /**
   * The search text the current `items` answer. Until it matches the typed text and loading has
   * ended, the overview announces no result - a count of the previous search would be wrong.
   */
  resultFor?: string
  maxLength?: number
}

export interface OverviewPageProps<T> {
  /** Document title, and the heading unless `heading` names one. */
  title: string
  /** Puts the figure into the heading itself, e.g. `(n) => '3 Spaces'` (#1914). */
  heading?: (count: number) => string
  /** The quiet figure beside a fixed heading; use instead of `heading`. */
  countLabel?: (count: number) => string
  /** One sentence under the heading, saying what the overview holds. */
  subtitle?: string
  /** Both omitted where an overview offers no creation at all. */
  createLabel?: string
  onCreate?: () => void
  items: T[]
  itemKey: (item: T) => string
  /** Everything the client-side search matches against - name, description, whatever fits. */
  searchText?: (item: T) => string
  /**
   * Hands the search to the caller, e.g. to a server query: `items` are then the result as it
   * stands, and the overview filters nothing itself. Omitted, the search runs over `searchText`.
   */
  search?: ControlledSearch
  /** Further controls beside the search, e.g. a type filter. */
  filters?: ReactNode
  /** Replaces the whole row of search and `filters`, e.g. with the asset filter row. */
  filterBar?: ReactNode
  /** Whether `filters` currently narrow `items` - an empty result then reads as "no match". */
  filtered?: boolean
  /** The size of the whole result where `items` hold only a part of it, e.g. one page. */
  total?: number
  searchPlaceholder?: string
  isLoading?: boolean
  error?: string | null
  /** Shown instead of the list when the overview holds no items at all. */
  emptyState?: ReactNode
  renderCard: (item: T) => ReactNode
  /** Rendered below the list, e.g. a button that loads the next page. */
  listFooter?: ReactNode
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
 * The shared frame of every overview (#1913): heading with count, "Neu" button, search and a grid
 * of cards - only cards, there is no table view (ADR-0039, Entscheidung 1). What an item looks like
 * stays with the caller - `renderCard` fills the grid, and `searchText` decides what the search
 * sees.
 */
export default function OverviewPage<T>({
  title,
  heading,
  countLabel,
  subtitle,
  createLabel,
  onCreate,
  items,
  itemKey,
  searchText,
  search,
  filters,
  filterBar,
  filtered = false,
  total,
  searchPlaceholder = 'Name oder Beschreibung …',
  isLoading = false,
  error = null,
  emptyState,
  renderCard,
  listFooter,
}: OverviewPageProps<T>) {
  const [ownQuery, setOwnQuery] = useState('')
  const query = search ? search.value : ownQuery
  const setQuery = search ? search.onChange : setOwnQuery

  const visible = useMemo(() => {
    const trimmed = query.trim()
    if (search || !trimmed || !searchText) return items
    return items.filter((item) => matches(searchText(item), trimmed))
  }, [items, query, search, searchText])

  // A narrowed result is still "a list with no match", never the overview's empty state - the
  // search and the filters have to stay in reach to widen it again.
  const narrowed = filtered || (search !== undefined && query.trim() !== '')
  const isEmpty = items.length === 0 && !narrowed
  const firstLoad = isLoading && items.length === 0 && !narrowed
  const showList = visible.length > 0
  const count = total ?? visible.length
  // Only a result that answers the text now in the field may be announced or called empty.
  const resultCurrent =
    !search || search.resultFor === undefined || (search.resultFor === query.trim() && !isLoading)

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'baseline',
          gap: 2,
          mb: subtitle ? 0.5 : 2.5,
          flexWrap: 'wrap',
        }}
      >
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
      {subtitle && (
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 2.5 }}>
          {subtitle}
        </Typography>
      )}

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {!isEmpty && filterBar && <Box sx={{ mb: 2.5 }}>{filterBar}</Box>}

      {!isEmpty && !filterBar && (
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
              htmlInput: { 'aria-label': 'Suchen', maxLength: search?.maxLength },
            }}
          />
          {filters}
        </Box>
      )}

      {/* Das Filtern verschiebt den Fokus nicht; ohne Live-Bereich bliebe das Ergebnis am
          Screenreader unbemerkt (accessibility.md, Prüfpunkt 2.8). */}
      <Box role="status" aria-live="polite" sx={visuallyHidden}>
        {resultCurrent && (query.trim() || filtered)
          ? searchResultMessage(count, query.trim())
          : ''}
      </Box>

      {firstLoad ? (
        <Box sx={{ py: 6, display: 'flex', justifyContent: 'center' }}>
          <CircularProgress size={24} aria-label="Liste wird geladen" />
        </Box>
      ) : isEmpty ? (
        emptyState
      ) : visible.length === 0 && !isLoading && resultCurrent ? (
        <Typography sx={{ color: 'text.secondary' }}>
          {searchResultMessage(0, query.trim())}
        </Typography>
      ) : null}

      {showList && (
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

      {showList && listFooter}
    </Box>
  )
}

const cardSx = {
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
  transition: (theme: Theme) =>
    theme.transitions.create(['border-color', 'transform'], {
      duration: theme.transitions.duration.shortest,
    }),
  '&:hover': {
    borderColor: blue[300],
    transform: 'translateY(-2px)',
  },
} as const

/**
 * The card shell of an overview with the quiet border and hover lift of mockup 1c. Motion stays on
 * transform only (guidelines 4.5). The whole card is one real link (new tab, middle click,
 * history); a card with controls of its own is an asset tile (`AssetTile`) instead.
 */
export function OverviewCard({ to, children }: { to: string; children: ReactNode }) {
  return (
    <ButtonBase component={RouterLink} to={to} sx={cardSx}>
      {children}
    </ButtonBase>
  )
}
