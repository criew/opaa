import { useMemo, useState, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import Checkbox from '@mui/material/Checkbox'
import InputAdornment from '@mui/material/InputAdornment'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import SearchIcon from '@mui/icons-material/Search'

export interface SelectionItem {
  id: string
  label: string
  /** One secondary fact (a kind, a key) in fg-3 after the label. */
  secondary?: string
}

export type CheckState = 'checked' | 'mixed' | 'unchecked'

export interface SelectionNoun {
  /** „Space“, „Ordner“ */
  one: string
  /** „Spaces“, „Ordner“ */
  many: string
}

/** From this many entries on the list gets a search, the count and the bulk choice. */
export const SEARCH_FROM = 7
/** From this many entries on only the visible rows are rendered. */
export const VIRTUAL_FROM = 100
const ROW_HEIGHT = 40
const VISIBLE_ROWS = 10
/** Labels longer than this are cut with an ellipsis and carry the full name as tooltip. */
const LONG_LABEL = 44

interface SelectionListProps {
  items: SelectionItem[]
  /** Accessible name of the group, e.g. „Spaces“. */
  label: string
  noun: SelectionNoun
  stateOf: (id: string) => CheckState
  /** Chosen through a parent: shown checked and not toggled on its own. */
  lockedOf?: (id: string) => boolean
  onToggle: (item: SelectionItem, checked: boolean) => void
  onToggleMany: (items: SelectionItem[], checked: boolean) => void
  /** Selected entries in total, also those the filter hides or another level holds. */
  selectedCount: number
  /** A control after the label, e.g. to open the entry's children. */
  renderAction?: (item: SelectionItem) => ReactNode
  /** Content under the row (an opened level); never used once the list is virtualized. */
  renderBelow?: (item: SelectionItem) => ReactNode
  /** Text of the first-time empty state. */
  emptyText: string
  /** Rendered without a checkbox: the row only opens its children. */
  selectable?: (item: SelectionItem) => boolean
  /** What a click on the label of a row without a checkbox does, e.g. open its children. */
  activate?: (item: SelectionItem) => void
  /** Whether the row's children are shown, for `aria-expanded` on its label button. */
  expandedOf?: (item: SelectionItem) => boolean | undefined
}

/**
 * The one list for objects of a source (spaces, areas, folders of one level). It grows with its
 * content: up to six entries a plain list of checkboxes; from seven a search, „n ausgewählt“ and
 * the bulk choice of what the filter shows; from a hundred only the visible rows are rendered.
 * The filter never changes the selection.
 */
export default function SelectionList({
  items,
  label,
  noun,
  stateOf,
  lockedOf = () => false,
  onToggle,
  onToggleMany,
  selectedCount,
  renderAction,
  renderBelow,
  emptyText,
  selectable = () => true,
  activate,
  expandedOf,
}: SelectionListProps) {
  const [query, setQuery] = useState('')
  const [scrollTop, setScrollTop] = useState(0)
  const searchable = items.length >= SEARCH_FROM
  const needle = query.trim().toLowerCase()
  const shown = useMemo(
    () =>
      needle === ''
        ? items
        : items.filter(
            (item) =>
              item.label.toLowerCase().includes(needle) || item.id.toLowerCase().includes(needle),
          ),
    [items, needle],
  )
  const virtual = items.length >= VIRTUAL_FROM
  const choosable = shown.filter((item) => selectable(item) && !lockedOf(item.id))
  const allChosen =
    choosable.length > 0 && choosable.every((item) => stateOf(item.id) === 'checked')
  const anyChosen = choosable.some((item) => stateOf(item.id) !== 'unchecked')

  if (items.length === 0) {
    return <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>{emptyText}</Typography>
  }

  const row = (item: SelectionItem) => (
    <SelectionRow
      key={item.id}
      item={item}
      state={lockedOf(item.id) ? 'checked' : stateOf(item.id)}
      locked={lockedOf(item.id)}
      selectable={selectable(item)}
      activate={activate}
      expanded={expandedOf?.(item)}
      onToggle={onToggle}
      action={renderAction?.(item)}
      below={virtual ? null : renderBelow?.(item)}
      height={virtual ? ROW_HEIGHT : undefined}
    />
  )

  const start = virtual ? Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - 5) : 0
  const end = virtual ? Math.min(shown.length, start + VISIBLE_ROWS + 10) : shown.length

  return (
    <Box>
      {searchable && (
        <Box sx={{ mb: 1 }}>
          <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1.5 }}>
            <TextField
              size="small"
              value={query}
              onChange={(event) => {
                setQuery(event.target.value)
                setScrollTop(0)
              }}
              placeholder={`${noun.many} durchsuchen`}
              sx={{ flex: '1 1 220px', minWidth: 0 }}
              slotProps={{
                htmlInput: { 'aria-label': `${noun.many} durchsuchen` },
                input: {
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchIcon aria-hidden sx={{ fontSize: 18 }} />
                    </InputAdornment>
                  ),
                },
              }}
            />
            <Typography
              sx={{ fontSize: 13, color: 'text.secondary', whiteSpace: 'nowrap' }}
              aria-live="polite"
            >
              {selectedCount} ausgewählt
            </Typography>
          </Box>
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mt: 0.5, ml: -1 }}>
            {choosable.length > 0 && !allChosen && (
              <Button size="small" variant="text" onClick={() => onToggleMany(choosable, true)}>
                {needle
                  ? `Alle ${choosable.length} Treffer auswählen`
                  : `Alle ${choosable.length} auswählen`}
              </Button>
            )}
            {anyChosen && (
              <Button size="small" variant="text" onClick={() => onToggleMany(choosable, false)}>
                {needle ? 'Treffer abwählen' : 'Auswahl aufheben'}
              </Button>
            )}
          </Box>
        </Box>
      )}
      {shown.length === 0 ? (
        <Box sx={{ py: 1 }}>
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Keine {noun.many} passen zu „{query.trim()}“.
          </Typography>
          <Button size="small" variant="text" sx={{ ml: -1 }} onClick={() => setQuery('')}>
            Filter zurücksetzen
          </Button>
        </Box>
      ) : (
        <Box
          role="group"
          aria-label={label}
          onScroll={virtual ? (event) => setScrollTop(event.currentTarget.scrollTop) : undefined}
          sx={{
            border: 1,
            borderColor: 'divider',
            borderRadius: '6px',
            ...(virtual
              ? { maxHeight: ROW_HEIGHT * VISIBLE_ROWS, overflowY: 'auto', position: 'relative' }
              : {}),
          }}
        >
          {virtual ? (
            <Box sx={{ height: shown.length * ROW_HEIGHT, position: 'relative' }}>
              <Box sx={{ position: 'absolute', top: start * ROW_HEIGHT, left: 0, right: 0 }}>
                {shown.slice(start, end).map(row)}
              </Box>
            </Box>
          ) : (
            shown.map(row)
          )}
        </Box>
      )}
      {virtual && shown.length > 0 && (
        <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.5 }}>
          {needle
            ? `${shown.length} von ${items.length} ${noun.many}`
            : `${items.length} ${noun.many}`}
        </Typography>
      )}
    </Box>
  )
}

function SelectionRow({
  item,
  state,
  locked,
  selectable,
  onToggle,
  action,
  below,
  height,
  activate,
  expanded,
}: {
  item: SelectionItem
  state: CheckState
  locked: boolean
  selectable: boolean
  activate?: (item: SelectionItem) => void
  expanded?: boolean
  onToggle: (item: SelectionItem, checked: boolean) => void
  action: ReactNode
  below: ReactNode
  height?: number
}) {
  const long = item.label.length > LONG_LABEL
  const text = (
    <Box
      component="span"
      sx={{
        minWidth: 0,
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        whiteSpace: 'nowrap',
        fontSize: 13.5,
      }}
    >
      {item.label}
      {item.secondary && (
        <Box component="span" sx={{ color: 'text.secondary', ml: 1, fontSize: 12.5 }}>
          {item.secondary}
        </Box>
      )}
    </Box>
  )
  const labelled = long ? (
    <Tooltip title={item.label} enterDelay={400} placement="top-start">
      {text}
    </Tooltip>
  ) : (
    text
  )
  return (
    <Box sx={{ '&:not(:last-of-type)': { borderBottom: 1, borderColor: 'divider' } }}>
      <Box sx={{ display: 'flex', alignItems: 'center', height, minHeight: 40, pr: 0.5 }}>
        {selectable ? (
          <Box
            component="label"
            sx={{
              display: 'flex',
              alignItems: 'center',
              flex: 1,
              minWidth: 0,
              cursor: locked ? 'default' : 'pointer',
            }}
          >
            <Checkbox
              size="small"
              checked={state === 'checked'}
              indeterminate={state === 'mixed'}
              disabled={locked}
              onChange={(event) => onToggle(item, event.target.checked)}
              slotProps={{
                input: {
                  'aria-checked': state === 'mixed' ? 'mixed' : state === 'checked',
                  'aria-description': locked
                    ? 'über den übergeordneten Eintrag gewählt'
                    : undefined,
                } as Record<string, unknown>,
              }}
            />
            {labelled}
          </Box>
        ) : activate ? (
          <ButtonBase
            onClick={() => activate(item)}
            aria-expanded={expanded}
            sx={{
              flex: 1,
              minWidth: 0,
              justifyContent: 'flex-start',
              alignSelf: 'stretch',
              pl: 1.5,
              fontFamily: 'inherit',
              fontWeight: 500,
              textAlign: 'left',
              borderRadius: '6px',
              '&:hover': { bgcolor: 'action.hover' },
              '&.Mui-focusVisible': { boxShadow: '0 0 0 3px rgba(18,144,239,0.32)' },
            }}
          >
            {text}
          </ButtonBase>
        ) : (
          <Box sx={{ display: 'flex', alignItems: 'center', flex: 1, minWidth: 0, pl: 1.5 }}>
            {labelled}
          </Box>
        )}
        {action}
      </Box>
      {below}
    </Box>
  )
}
