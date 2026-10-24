import { useRef, useState, type KeyboardEvent, type SyntheticEvent } from 'react'
import Autocomplete, { type AutocompleteChangeDetails } from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import ChevronRight from '@mui/icons-material/ChevronRight'
import ChevronLeft from '@mui/icons-material/ChevronLeft'
import CheckBoxIcon from '@mui/icons-material/CheckBox'
import BlankIcon from '@mui/icons-material/CheckBoxOutlineBlank'
import IndeterminateIcon from '@mui/icons-material/IndeterminateCheckBox'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'

/** One object of a source: a space, a bucket, a folder. */
export interface PickNode {
  key: string
  label: string
  /** The names above it, for the path shown with it and for typed paths. */
  trail: string[]
  /** It can be opened to list what lies below. */
  branch?: boolean
  /** A second word that tells equal names apart, e.g. „Geteilte Ablage“. */
  secondary?: string
  /** Why the node cannot be chosen; it stays visible with this reason. */
  disabledReason?: string
  /** Whatever the connector needs to turn the node back into its values. */
  data?: unknown
}

export interface PickListing {
  nodes: PickNode[]
  /** False when the source refused to list; `message` says why and the manual entry remains. */
  complete: boolean
  message?: string | null
}

type Pseudo = '__up' | '__all' | '__manual' | '__more' | '__status' | '__retry' | '__reset'

interface Option extends PickNode {
  pseudo?: Pseudo
}

interface CacheEntry {
  status: 'loading' | 'ok' | 'error'
  listing?: PickListing
  error?: string
}

interface PickComboProps {
  id: string
  label: string
  hint?: string
  error?: string | null
  /** Singular and plural of what is chosen, for counts and messages. */
  noun: [string, string]
  selected: PickNode[]
  onChange: (next: PickNode[]) => void
  /** Lists what lies below `parent` (null: the top); `term` only matters with `serverSearch`. */
  load: (parent: PickNode | null, term: string) => Promise<PickListing>
  /** The source searches itself (SharePoint sites); otherwise the loaded level is filtered here. */
  serverSearch?: boolean
  /** Nodes can be opened; a typed path („Projekte/2024“) loads the levels on the way. */
  hierarchical?: boolean
  single?: boolean
  max?: number
  /** A node that stands for everything and excludes every other choice, e.g. „/ (alles)“. */
  allNode?: PickNode
  /** Turns typed text into a node of its own, for a source that cannot list. */
  manual?: (text: string) => PickNode | null
  manualLabel?: (text: string) => string
  /** A changed value starts the listings afresh (new address, new credentials). */
  loadKey: string
  placeholder?: string
}

const SHOWN_LIMIT = 100
const CHIPS_COLLAPSED = 4
const LOADING_DELAY = 300

const pathOf = (node: PickNode) => [...node.trail, node.label]

function covers(ancestor: PickNode, node: PickNode): boolean {
  const a = pathOf(ancestor)
  const n = pathOf(node)
  return a.length < n.length && a.every((part, i) => part === n[i])
}

function pathText(node: PickNode): string {
  return pathOf(node).join(' / ')
}

/**
 * The selection of foreign objects from a source - spaces, buckets, folders, sites. A combo box
 * with type-ahead filter and multiple choice; the chosen ones stand as chips below, from five on
 * folded to „+n weitere“. Hierarchical sources list one level at a time: an option opens with the
 * arrow (or Pfeil rechts), and a typed path loads the levels on the way. A refused listing leaves
 * the manual entry.
 */
export default function PickCombo({
  id,
  label,
  hint,
  error,
  noun,
  selected,
  onChange,
  load,
  serverSearch = false,
  hierarchical = false,
  single = false,
  max,
  allNode,
  manual,
  manualLabel,
  loadKey,
  placeholder,
}: PickComboProps) {
  const [open, setOpen] = useState(false)
  const [input, setInput] = useState('')
  // the listings by level: state for rendering, mirrored in a ref so a finished load can walk on
  // to the next level of a typed path at once
  const [cache, setCache] = useState<{ key: string; entries: Record<string, CacheEntry> }>({
    key: loadKey,
    entries: {},
  })
  const cacheRef = useRef(cache)
  const entries = cache.key === loadKey ? cache.entries : {}
  const known = () => (cacheRef.current.key === loadKey ? cacheRef.current.entries : {})
  const putEntry = (key: string, entry: CacheEntry, forKey: string) => {
    const base = cacheRef.current.key === forKey ? cacheRef.current.entries : {}
    const next = { key: forKey, entries: { ...base, [key]: entry } }
    cacheRef.current = next
    setCache(next)
  }
  const [slowLoading, setSlowLoading] = useState(false)
  const [chipsExpanded, setChipsExpanded] = useState(false)
  const highlighted = useRef<Option | null>(null)
  const latestInput = useRef('')
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const helperId = `${id}-helper`

  const entryKey = (parent: PickNode | null, term: string) =>
    `${parent?.key ?? ''}${serverSearch ? `|${term}` : ''}`

  function startLoad(parent: PickNode | null, term: string, onDone?: () => void) {
    const key = entryKey(parent, term)
    const mineKey = loadKey
    putEntry(key, { status: 'loading' }, mineKey)
    const timer = setTimeout(() => setSlowLoading(true), LOADING_DELAY)
    load(parent, term)
      .then((listing) => ({ status: 'ok' as const, listing }))
      .catch((err: unknown) => ({
        status: 'error' as const,
        error:
          err instanceof Error && err.message ? err.message : 'Die Liste ließ sich nicht laden.',
      }))
      .then((entry) => {
        clearTimeout(timer)
        setSlowLoading(false)
        putEntry(key, entry, mineKey)
        onDone?.()
      })
  }

  /** The level a typed path points at: its node, the term below it, and a level still missing. */
  function resolve(text: string, levels: Record<string, CacheEntry>) {
    if (!hierarchical || !text.includes('/'))
      return { parent: null, term: text.trim(), missing: false, unknown: null as string | null }
    const parts = text.split('/')
    const term = (parts.pop() ?? '').trim()
    const names = parts.map((p) => p.trim()).filter(Boolean)
    let parent: PickNode | null = null
    for (const name of names) {
      const level: CacheEntry | undefined = levels[entryKey(parent, '')]
      if (!level || level.status === 'loading')
        return { parent, term, missing: !level, unknown: null }
      if (level.status === 'error') return { parent, term, missing: false, unknown: null }
      const next: PickNode | undefined = level.listing?.nodes.find(
        (node: PickNode) => node.label.toLowerCase() === name.toLowerCase(),
      )
      if (!next) return { parent, term, missing: false, unknown: name }
      parent = next
    }
    return {
      parent,
      term,
      missing: !levels[entryKey(parent, serverSearch ? term : '')],
      unknown: null,
    }
  }

  /** Loads whatever the current input still needs, level by level. */
  function ensure(text: string) {
    const current = known()
    const target = resolve(text, current)
    if (target.unknown) return
    const key = entryKey(target.parent, serverSearch ? target.term : '')
    if (current[key]) return
    if (serverSearch) {
      if (searchTimer.current) clearTimeout(searchTimer.current)
      searchTimer.current = setTimeout(() => startLoad(target.parent, target.term), 300)
      return
    }
    // the next level of a typed path follows once this one is known
    startLoad(target.parent, '', () => ensure(latestInput.current))
  }

  const target = resolve(input, entries)
  const levelEntry = entries[entryKey(target.parent, serverSearch ? target.term : '')]
  const levelNodes = levelEntry?.listing?.nodes ?? []
  const matches = serverSearch
    ? levelNodes
    : levelNodes.filter((node) =>
        target.term === ''
          ? true
          : `${node.label} ${node.secondary ?? ''}`
              .toLowerCase()
              .includes(target.term.toLowerCase()),
      )
  const isSelected = (node: PickNode) => selected.some((s) => s.key === node.key)
  const coveredBy = (node: PickNode) => selected.find((s) => covers(s, node))
  const partly = (node: PickNode) => !isSelected(node) && selected.some((s) => covers(node, s))
  const full = max !== undefined && selected.length >= max

  const pseudo = (kind: Pseudo, text: string): Option => ({
    key: `${kind}:${text}`,
    label: text,
    trail: [],
    pseudo: kind,
  })

  const options: Option[] = []
  if (target.parent) {
    options.push(pseudo('__status', `In ${pathText(target.parent)}`))
    const above = target.parent.trail[target.parent.trail.length - 1]
    options.push(pseudo('__up', above ? `Zurück zu „${above}“` : 'Zurück zur obersten Ebene'))
  }
  if (!target.parent && allNode && target.term === '') options.push(allNode)
  if (target.unknown) {
    options.push(pseudo('__status', `„${target.unknown}“ gibt es hier nicht.`))
  } else if (!levelEntry || levelEntry.status === 'loading') {
    if (slowLoading || serverSearch) options.push(pseudo('__status', 'Wird geladen …'))
  } else if (levelEntry.status === 'error') {
    options.push(pseudo('__status', levelEntry.error ?? 'Die Liste ließ sich nicht laden.'))
    options.push(pseudo('__retry', 'Erneut versuchen'))
  } else {
    const listing = levelEntry.listing!
    if (!listing.complete && listing.message) options.push(pseudo('__status', listing.message))
    const selectable = matches.filter(
      (node) => !isSelected(node) && !coveredBy(node) && !node.disabledReason,
    )
    if (!single && selectable.length >= 2 && !full) {
      options.push(
        pseudo(
          '__all',
          target.term
            ? `Alle ${selectable.length} Treffer auswählen`
            : `Alle ${selectable.length} ${noun[1]}${hierarchical ? ' dieser Ebene' : ''} auswählen`,
        ),
      )
    }
    options.push(...matches.slice(0, SHOWN_LIMIT))
    if (matches.length > SHOWN_LIMIT) {
      options.push(
        pseudo('__more', `Noch ${matches.length - SHOWN_LIMIT} weitere – Suchbegriff eingrenzen`),
      )
    }
    if (listing.complete && levelNodes.length === 0) {
      options.push(
        pseudo(
          '__status',
          target.parent
            ? `Keine ${noun[1]} unter „${target.parent.label}“.`
            : `Keine ${noun[1]} vorhanden.`,
        ),
      )
    } else if (matches.length === 0 && target.term !== '') {
      options.push(pseudo('__status', `Keine Treffer für „${target.term}“.`))
      options.push(pseudo('__reset', 'Suche zurücksetzen'))
    }
  }
  const manualNode = manual && target.term ? manual(input.trim()) : null
  if (manualNode && !matches.some((n) => n.key === manualNode.key)) {
    options.push({
      ...pseudo('__manual', manualLabel?.(input.trim()) ?? `„${input.trim()}“ übernehmen`),
      data: manualNode,
    })
  }

  function add(nodes: PickNode[]) {
    if (single) {
      onChange(nodes.slice(0, 1))
      setOpen(false)
      setInput('')
      return
    }
    let next = selected.filter((s) => !(allNode && s.key === allNode.key))
    for (const node of nodes) {
      if (allNode && node.key === allNode.key) {
        onChange([node])
        return
      }
      if (next.some((s) => s.key === node.key) || next.some((s) => covers(s, node))) continue
      // a chosen folder takes the place of chosen folders below it
      next = next.filter((s) => !covers(node, s))
      if (max !== undefined && next.length >= max) break
      next.push(node)
    }
    onChange(next)
  }

  function drill(node: PickNode) {
    const text = `${pathOf(node).join('/')}/`
    setInput(text)
    latestInput.current = text
    ensure(text)
  }

  function goUp() {
    const parent = target.parent
    if (!parent) return
    const text = parent.trail.length ? `${parent.trail.join('/')}/` : ''
    setInput(text)
    latestInput.current = text
    ensure(text)
  }

  function handleChange(
    _event: SyntheticEvent,
    _value: Option[],
    reason: string,
    details?: AutocompleteChangeDetails<Option>,
  ) {
    const option = details?.option
    if (reason === 'clear') {
      setInput('')
      return
    }
    if (!option) return
    switch (option.pseudo) {
      case '__up':
        return goUp()
      case '__all':
        return add(
          matches.filter((node) => !isSelected(node) && !coveredBy(node) && !node.disabledReason),
        )
      case '__manual':
        return add([option.data as PickNode])
      case '__retry': {
        startLoad(target.parent, serverSearch ? target.term : '')
        return
      }
      case '__reset': {
        const text = target.parent ? `${pathOf(target.parent).join('/')}/` : ''
        setInput(text)
        latestInput.current = text
        return
      }
      case '__more':
      case '__status':
        return
      default:
        if (reason === 'removeOption' || isSelected(option)) {
          onChange(selected.filter((s) => s.key !== option.key))
        } else if (!coveredBy(option)) {
          add([option])
        }
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const current = highlighted.current
    if (!hierarchical || !open) return
    if (event.key === 'ArrowRight' && current?.branch && !current.pseudo) {
      event.preventDefault()
      drill(current)
    } else if (event.key === 'ArrowLeft' && target.parent && target.term === '') {
      event.preventDefault()
      goUp()
    }
  }

  // the state of the top listing stays visible under the field once the list is closed
  const root = entries[entryKey(null, '')]
  const notice: { text: string; tone: 'error' | 'quiet'; retry?: boolean } | null =
    !root || root.status === 'loading'
      ? null
      : root.status === 'error'
        ? { text: root.error ?? 'Die Liste ließ sich nicht laden.', tone: 'error', retry: true }
        : !root.listing?.complete && root.listing?.message
          ? { text: root.listing.message, tone: 'quiet' }
          : root.listing?.nodes.length === 0
            ? { text: `Keine ${noun[1]} vorhanden.`, tone: 'quiet' }
            : null

  const visibleChips = chipsExpanded ? selected : selected.slice(0, CHIPS_COLLAPSED)
  const hidden = selected.length - visibleChips.length

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'baseline', flexWrap: 'wrap', columnGap: 1 }}>
        <FieldLabel htmlFor={id}>{label}</FieldLabel>
        {!single && selected.length > 0 && (
          <Typography
            sx={{ fontSize: 12, color: 'text.secondary', whiteSpace: 'nowrap' }}
            aria-live="polite"
          >
            · {selected.length} ausgewählt
          </Typography>
        )}
      </Box>
      <Autocomplete<Option, true, false, false>
        id={id}
        multiple
        open={open}
        onOpen={() => {
          setOpen(true)
          ensure(input)
        }}
        onClose={(_e, reason) => {
          if (reason !== 'selectOption' && reason !== 'removeOption') setOpen(false)
        }}
        disableCloseOnSelect={!single}
        options={options}
        value={selected as Option[]}
        onChange={handleChange}
        inputValue={input}
        onInputChange={(_e, value, reason) => {
          if (reason === 'reset' || reason === 'selectOption' || reason === 'removeOption') return
          setInput(value)
          latestInput.current = value
          ensure(value)
        }}
        filterOptions={(o) => o}
        isOptionEqualToValue={(a, b) => a.key === b.key}
        getOptionLabel={(o) => o.label}
        getOptionDisabled={(o) =>
          o.pseudo === '__status' || o.pseudo === '__more' || Boolean(o.disabledReason)
        }
        onHighlightChange={(_e, option) => {
          highlighted.current = option
        }}
        renderValue={() => null}
        noOptionsText={slowLoading ? 'Wird geladen …' : 'Keine Einträge'}
        slotProps={{ listbox: { sx: { maxHeight: 360 } } }}
        renderOption={(props, option) => {
          const { key, ...rest } = props
          if (option.pseudo) {
            const quiet = option.pseudo === '__status' || option.pseudo === '__more'
            return (
              <li key={key} {...rest}>
                <Box
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1,
                    fontSize: 13.5,
                    color: quiet ? 'text.secondary' : 'primary.main',
                    fontWeight: quiet ? 400 : 500,
                  }}
                >
                  {option.pseudo === '__up' && <ChevronLeft fontSize="small" aria-hidden />}
                  {option.label}
                </Box>
              </li>
            )
          }
          const chosen = isSelected(option)
          const covering = coveredBy(option)
          const mixed = partly(option)
          // icons, not controls: an option holds nothing focusable of its own
          const Mark = chosen || covering ? CheckBoxIcon : mixed ? IndeterminateIcon : BlankIcon
          return (
            <li key={key} {...rest} data-pick="">
              <Mark
                aria-hidden
                fontSize="small"
                sx={{
                  mr: 1,
                  flex: 'none',
                  color: chosen || mixed ? 'primary.main' : 'text.secondary',
                  opacity: covering || option.disabledReason ? 0.5 : 1,
                }}
              />
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography
                  sx={{
                    fontSize: 13.5,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                  title={option.label}
                >
                  {option.label}
                </Typography>
                {(option.secondary || covering || mixed || option.disabledReason) && (
                  <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
                    {option.disabledReason ??
                      (covering
                        ? `enthalten in „${covering.label}“`
                        : mixed
                          ? 'teilweise ausgewählt'
                          : option.secondary)}
                  </Typography>
                )}
              </Box>
              {hierarchical && option.branch && (
                <Tooltip title="Unterordner anzeigen">
                  <Box
                    component="span"
                    aria-hidden
                    data-drill={option.label}
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={(e) => {
                      e.stopPropagation()
                      drill(option)
                    }}
                    sx={{
                      display: 'inline-flex',
                      p: 0.75,
                      borderRadius: 1,
                      color: 'text.secondary',
                      '&:hover': { bgcolor: 'action.hover' },
                    }}
                  >
                    <ChevronRight fontSize="small" />
                  </Box>
                </Tooltip>
              )}
            </li>
          )
        }}
        renderInput={(params) => (
          <TextField
            {...params}
            size="small"
            placeholder={placeholder}
            error={Boolean(error)}
            helperText={error ?? hint}
            onKeyDown={handleKeyDown}
            slotProps={{
              ...params.slotProps,
              formHelperText: { id: helperId },
            }}
          />
        )}
      />
      {notice && !open && (
        <Box
          role="status"
          sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1, mt: 1 }}
        >
          <Typography
            sx={{ fontSize: 13, color: notice.tone === 'error' ? 'error.main' : 'text.secondary' }}
          >
            {notice.text}
          </Typography>
          {notice.retry && (
            <Button size="small" variant="text" onClick={() => startLoad(null, '')}>
              Erneut laden
            </Button>
          )}
        </Box>
      )}
      {!single && selected.length > 0 && (
        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mt: 1, alignItems: 'center' }}>
          {visibleChips.map((node) => (
            <Tooltip key={node.key} title={pathText(node)}>
              <Chip
                size="small"
                variant="outlined"
                label={pathText(node)}
                onDelete={() => onChange(selected.filter((s) => s.key !== node.key))}
                sx={{
                  maxWidth: '100%',
                  '& .MuiChip-label': { overflow: 'hidden', textOverflow: 'ellipsis' },
                }}
              />
            </Tooltip>
          ))}
          {hidden > 0 && (
            <Button size="small" variant="text" onClick={() => setChipsExpanded(true)}>
              +{hidden} weitere
            </Button>
          )}
          {chipsExpanded && selected.length > CHIPS_COLLAPSED && (
            <Button size="small" variant="text" onClick={() => setChipsExpanded(false)}>
              Weniger anzeigen
            </Button>
          )}
          {selected.length >= 2 && (
            <Button size="small" variant="text" onClick={() => onChange([])}>
              Alle entfernen
            </Button>
          )}
        </Box>
      )}
      {single && selected[0] && (
        <Typography sx={{ fontSize: 13, mt: 0.5, overflowWrap: 'anywhere' }}>
          Gewählt: {pathText(selected[0])}
        </Typography>
      )}
    </Box>
  )
}
