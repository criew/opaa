import { useCallback, useContext, useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { SourceIssueContext, issueList } from '../sources/sourceIssueContext'
import Box from '@mui/material/Box'
import Breadcrumbs from '@mui/material/Breadcrumbs'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import Checkbox from '@mui/material/Checkbox'
import IconButton from '@mui/material/IconButton'
import Link from '@mui/material/Link'
import Skeleton from '@mui/material/Skeleton'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import SearchIcon from '@mui/icons-material/Search'
import InputAdornment from '@mui/material/InputAdornment'
import PickedList from './PickedList'
import {
  countOf,
  type PickedItem,
  type PickerCrumb,
  type PickerEntry,
  type PickerLevel,
  type PickerNoun,
} from './pickerTypes'

/** The selection shows this many entries of a level at once, then „Weitere anzeigen". */
const PAGE = 100
/** Up to this many flat entries need no split view: a plain checkbox list is enough. */
const SMALL = 6
/** From this many entries a level gets a filter field. */
const SEARCH_FROM = 7
/** Below this container width the two columns stack behind a switch. */
const SPLIT_FROM = 640
/** From this container width the parent level stands as its own column (Miller columns). */
const MILLER_FROM = 960

interface SplitPickerProps {
  idPrefix: string
  /** The group's visible title, also its accessible name („Ordner", „Spaces"). */
  title: string
  /** The name of the source's top level in breadcrumb and paths („Nextcloud", „Sites"). */
  rootLabel: string
  noun: PickerNoun
  /** What a level is called, by depth (for the filter field and empty texts). */
  levelNoun?: (depth: number) => PickerNoun
  load: (trail: PickerCrumb[], query?: string) => Promise<PickerLevel>
  selected: PickedItem[]
  onChange: (next: PickedItem[]) => void
  max?: number
  /** What an empty selection means („Ohne Auswahl wird die ganze Freigabe gelesen."). */
  emptySelection: string
  /** Asks the source itself instead of filtering what is loaded, from this text on (a site URL). */
  remoteQuery?: (trail: PickerCrumb[], query: string) => boolean
  remoteQueryLabel?: string
  /** Shows the parent level beside the current one on wide screens. */
  millerColumns?: boolean
  /** Why chosen objects could not be read at the last test, by key. */
  problems?: Record<string, string>
  /** An entry typed by hand when the listing is refused or does not show it. */
  manualEntry?: ReactNode
  /** The form values the selection stands for; their issues show under the title. */
  issueFields?: string[]
}

type Loaded =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ok'; level: PickerLevel }

const trailKey = (trail: PickerCrumb[], query = '') =>
  `${trail.map((crumb) => crumb.key).join('\u0000')}\u0001${query}`

/** Tracks the width of an element, so the layout follows its container rather than the window. */
function useWidth() {
  const ref = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(1024)
  useEffect(() => {
    const element = ref.current
    if (!element || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])
  return { ref, width }
}

/** True once `active` has lasted 300 ms, so a quick answer never flashes a placeholder. */
function useDelayed(active: boolean) {
  const [shown, setShown] = useState(false)
  useEffect(() => {
    if (!active) {
      setShown(false)
      return
    }
    const timer = setTimeout(() => setShown(true), 300)
    return () => clearTimeout(timer)
  }, [active])
  return shown
}

/**
 * The selection of Variante C: on the left, browsing the source level by level (breadcrumb,
 * filter, list; a parent column on wide screens), on the right „Ausgewählt (n)" with every chosen
 * object's full path and a remove button. A flat source with a handful of objects gets a plain
 * checkbox list instead; below 640 px the two columns stack behind a switch.
 *
 * Choosing an object includes everything below it: its children show as included, and a parent
 * holding chosen children shows as partly chosen (`aria-checked="mixed"`) - choosing it replaces
 * them. A filter never drops a choice, and the count includes what the filter hides.
 */
export default function SplitPicker({
  idPrefix,
  title,
  rootLabel,
  noun,
  levelNoun = () => noun,
  load,
  selected,
  onChange,
  max,
  emptySelection,
  remoteQuery,
  remoteQueryLabel = 'Öffnen',
  millerColumns = false,
  problems,
  manualEntry,
  issueFields = [],
}: SplitPickerProps) {
  const headingId = useId()
  const issues = issueList(useContext(SourceIssueContext)).filter(
    (issue) => issue.field !== undefined && issueFields.includes(issue.field),
  )
  const issueLine = issues.length > 0 && <SelectionIssues messages={issues.map((i) => i.message)} />
  const { ref, width } = useWidth()
  const [trail, setTrail] = useState<PickerCrumb[]>([])
  const [query, setQuery] = useState('')
  const [remote, setRemote] = useState('')
  const [limit, setLimit] = useState(PAGE)
  const [view, setView] = useState<'browse' | 'selected'>('browse')
  const [cache, setCache] = useState<Record<string, Loaded>>({})
  const [notice, setNotice] = useState<string | null>(null)
  const listTop = useRef<HTMLDivElement>(null)

  const fetchLevel = useCallback(
    async (path: PickerCrumb[], remoteText = '') => {
      const key = trailKey(path, remoteText)
      setCache((prev) => ({ ...prev, [key]: { status: 'loading' } }))
      try {
        const level = await load(path, remoteText || undefined)
        setCache((prev) => ({ ...prev, [key]: { status: 'ok', level } }))
      } catch (err) {
        const message =
          err instanceof Error && err.message ? err.message : 'Die Liste ließ sich nicht laden.'
        setCache((prev) => ({ ...prev, [key]: { status: 'error', message } }))
      }
    },
    [load],
  )

  const currentKey = trailKey(trail, trail.length === 0 ? remote : '')
  const current = cache[currentKey]
  useEffect(() => {
    if (!current) void fetchLevel(trail, trail.length === 0 ? remote : '')
  }, [current, fetchLevel, trail, remote])

  const loading = current === undefined || current.status === 'loading'
  const showSkeleton = useDelayed(loading)
  const level = current?.status === 'ok' ? current.level : null
  const entries = level?.entries ?? []
  const levelName = levelNoun(trail.length)
  const filtered = query.trim()
    ? entries.filter((entry) => entry.name.toLowerCase().includes(query.trim().toLowerCase()))
    : entries
  const shown = filtered.slice(0, limit)

  const selectedKeys = new Set(selected.map((item) => item.key))
  const coveredBy = trail.find((crumb) => selectedKeys.has(crumb.key))
  const isMixed = (entry: PickerEntry) =>
    !selectedKeys.has(entry.key) &&
    selected.some((item) => item.trail.some((crumb) => crumb.key === entry.key))

  function announce(text: string) {
    setNotice(text)
  }

  function choose(entries: PickerEntry[]) {
    const fresh = entries.filter((entry) => !selectedKeys.has(entry.key))
    if (fresh.length === 0) return
    const freshKeys = new Set(fresh.map((entry) => entry.key))
    // a chosen parent includes its children: chosen descendants make way for it
    const kept = selected.filter((item) => !item.trail.some((crumb) => freshKeys.has(crumb.key)))
    const next = [
      ...kept,
      ...fresh.map((entry) => ({ key: entry.key, name: entry.name, trail, raw: entry.raw })),
    ]
    if (max && next.length > max) {
      announce(`Höchstens ${countOf(max, noun)} je Bibliothek; ${countOf(next.length, noun)} wären gewählt.`)
      return
    }
    onChange(next)
    announce(
      fresh.length === 1
        ? `„${fresh[0].name}“ ausgewählt, ${selected.length === 0 ? 1 : next.length} insgesamt.`
        : `${countOf(fresh.length, noun)} ausgewählt, ${next.length} insgesamt.`,
    )
  }

  function remove(keys: Set<string>) {
    const next = selected.filter((item) => !keys.has(item.key))
    onChange(next)
    announce(`${countOf(selected.length - next.length, noun)} entfernt, ${next.length} gewählt.`)
  }

  function toggle(entry: PickerEntry) {
    if (selectedKeys.has(entry.key)) remove(new Set([entry.key]))
    else choose([entry])
  }

  function open(path: PickerCrumb[]) {
    setTrail(path)
    setQuery('')
    setLimit(PAGE)
    requestAnimationFrame(() => listTop.current?.focus())
  }

  const choosable = filtered.filter((entry) => entry.selectable)
  const allChosen = choosable.length > 0 && choosable.every((entry) => selectedKeys.has(entry.key))

  // The small case: one flat level of a handful of objects needs neither columns nor search.
  const small =
    trail.length === 0 &&
    !remoteQuery &&
    level !== null &&
    level.complete &&
    entries.length <= SMALL &&
    entries.every((entry) => entry.selectable && !entry.hasChildren)

  const split = width >= SPLIT_FROM
  const miller = millerColumns && width >= MILLER_FROM && trail.length > 0
  const parentLevel = miller ? cache[trailKey(trail.slice(0, -1), trail.length === 1 ? remote : '')] : undefined

  const status = (
    <Box role="status" aria-live="polite" sx={visuallyHidden}>
      {notice}
    </Box>
  )

  if (small) {
    const extra = selected.filter((item) => !entries.some((entry) => entry.key === item.key))
    return (
      <Box
        ref={ref}
        component="fieldset"
        data-source-field="selection"
        tabIndex={-1}
        sx={{ border: 0, m: 0, p: 0, minWidth: 0 }}
      >
        <Typography component="legend" sx={{ fontSize: 16, fontWeight: 600, mb: 1, p: 0 }}>
          {title}
        </Typography>
        {issueLine}
        {entries.length === 0 && extra.length === 0 && (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Die Quelle zeigt keine {noun.many}.
          </Typography>
        )}
        {[...entries, ...extra.map((item) => ({ ...item, selectable: true, hasChildren: false }))].map(
          (entry) => (
            <Box key={entry.key} sx={{ display: 'flex', alignItems: 'center', minHeight: 40 }}>
              <Checkbox
                id={`${idPrefix}-small-${entry.key}`}
                size="small"
                checked={selectedKeys.has(entry.key)}
                onChange={() => toggle(entry as PickerEntry)}
              />
              <Typography
                component="label"
                htmlFor={`${idPrefix}-small-${entry.key}`}
                sx={{ fontSize: 14, cursor: 'pointer', overflowWrap: 'anywhere' }}
              >
                {entry.name}
                {'hint' in entry && entry.hint && (
                  <Box component="span" sx={{ color: 'text.secondary', fontSize: 12.5, ml: 1 }}>
                    {entry.hint}
                  </Box>
                )}
              </Typography>
            </Box>
          ),
        )}
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
          {selected.length === 0 ? emptySelection : `${countOf(selected.length, noun)} ausgewählt`}
        </Typography>
        {manualEntry}
        {status}
      </Box>
    )
  }

  const browse = (
    <Box sx={{ minWidth: 0, display: 'flex', flexDirection: 'column', gap: 1 }}>
      <Breadcrumbs
        aria-label="Ebene"
        maxItems={4}
        itemsBeforeCollapse={1}
        itemsAfterCollapse={2}
        sx={{ fontSize: 13, '& .MuiBreadcrumbs-ol': { flexWrap: 'wrap' } }}
      >
        {[{ key: '', name: rootLabel }, ...trail].map((crumb, index, all) =>
          index === all.length - 1 ? (
            <Typography
              key={crumb.key || 'root'}
              aria-current="location"
              sx={{ ...crumbSx, fontSize: 13, fontWeight: 600 }}
              title={crumb.name}
            >
              {crumb.name}
            </Typography>
          ) : (
            <Link
              key={crumb.key || 'root'}
              component="button"
              type="button"
              onClick={() => open(trail.slice(0, index))}
              title={crumb.name}
              sx={{ ...crumbSx, fontSize: 13, verticalAlign: 'bottom' }}
            >
              {crumb.name}
            </Link>
          ),
        )}
      </Breadcrumbs>

      {(entries.length >= SEARCH_FROM || query !== '' || (remoteQuery && trail.length === 0)) && (
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
          <TextField
            size="small"
            fullWidth
            value={query}
            onChange={(e) => {
              setQuery(e.target.value)
              setLimit(PAGE)
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && remoteQuery?.(trail, query.trim())) {
                e.preventDefault()
                setRemote(query.trim())
                setQuery('')
              }
            }}
            placeholder={remoteQuery && trail.length === 0 ? 'Name oder Adresse' : 'Suchen'}
            slotProps={{
              htmlInput: { 'aria-label': `${levelName.many} suchen` },
              input: {
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchIcon fontSize="small" aria-hidden />
                  </InputAdornment>
                ),
              },
            }}
          />
          {remoteQuery?.(trail, query.trim()) && (
            <Button
              variant="outlined"
              onClick={() => {
                setRemote(query.trim())
                setQuery('')
              }}
              sx={{ flex: 'none' }}
            >
              {remoteQueryLabel}
            </Button>
          )}
        </Box>
      )}

      {level && !level.complete && level.message && (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Liste unvollständig: {level.message}
        </Typography>
      )}

      <Box sx={{ display: 'flex', alignItems: 'center', minHeight: 32, gap: 1, flexWrap: 'wrap' }}>
        <Typography
          ref={listTop}
          tabIndex={-1}
          sx={{ fontSize: 12.5, color: 'text.secondary', flex: 1, outline: 'none' }}
        >
          {level
            ? query
              ? `${filtered.length.toLocaleString('de-DE')} von ${countOf(entries.length, levelName)}`
              : countOf(entries.length, levelName)
            : ''}
        </Typography>
        {choosable.length >= 2 && !coveredBy && (
          <Button
            size="small"
            onClick={() =>
              allChosen
                ? remove(new Set(choosable.map((entry) => entry.key)))
                : choose(choosable)
            }
          >
            {allChosen
              ? `Alle ${choosable.length.toLocaleString('de-DE')} abwählen`
              : query
                ? `Alle ${choosable.length.toLocaleString('de-DE')} Treffer auswählen`
                : `Alle ${choosable.length.toLocaleString('de-DE')} auswählen`}
          </Button>
        )}
      </Box>

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: miller ? 'minmax(0, 2fr) minmax(0, 3fr)' : 'minmax(0, 1fr)',
          gap: 1,
        }}
      >
        {miller && (
          <ParentColumn
            loaded={parentLevel}
            activeKey={trail[trail.length - 1].key}
            onOpen={(entry) =>
              open([...trail.slice(0, -1), { key: entry.key, name: entry.name, raw: entry.raw }])
            }
          />
        )}
        <Box sx={listBoxSx}>
          {loading && showSkeleton && (
            <Box aria-busy="true" aria-label={`${levelName.many} werden geladen`} sx={{ p: 1 }}>
              {[0, 1, 2, 3, 4].map((row) => (
                <Skeleton key={row} variant="rounded" height={28} sx={{ mb: 1 }} />
              ))}
            </Box>
          )}
          {current?.status === 'error' && (
            <Box sx={{ p: 1.5 }}>
              <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>
                {/403|verweigert|Berechtig|AccessDenied/i.test(current.message)
                  ? 'Kein Zugriff auf diese Ebene.'
                  : 'Die Liste ließ sich nicht laden.'}
              </Typography>
              <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
                {current.message}
              </Typography>
              <Button
                size="small"
                sx={{ mt: 1 }}
                onClick={() => void fetchLevel(trail, trail.length === 0 ? remote : '')}
              >
                Erneut versuchen
              </Button>
            </Box>
          )}
          {level && entries.length === 0 && (
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary', p: 1.5 }}>
              {trail.length === 0 && remote
                ? `Nichts gefunden für „${remote}“.`
                : `In „${trail.length ? trail[trail.length - 1].name : rootLabel}“ gibt es keine ${levelName.many}.`}
            </Typography>
          )}
          {level && entries.length > 0 && filtered.length === 0 && (
            <Box sx={{ p: 1.5 }}>
              <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                Keine Treffer für „{query}“.
              </Typography>
              <Button size="small" sx={{ mt: 0.5 }} onClick={() => setQuery('')}>
                Suche zurücksetzen
              </Button>
            </Box>
          )}
          {shown.length > 0 && (
            <Box component="ul" aria-labelledby={headingId} sx={{ listStyle: 'none', m: 0, p: 0 }}>
              {shown.map((entry) => (
                <EntryRow
                  key={entry.key}
                  idPrefix={idPrefix}
                  entry={entry}
                  checked={selectedKeys.has(entry.key)}
                  mixed={isMixed(entry)}
                  coveredBy={coveredBy?.name ?? null}
                  onToggle={() => toggle(entry)}
                  onOpen={() =>
                    open([...trail, { key: entry.key, name: entry.name, raw: entry.raw }])
                  }
                />
              ))}
            </Box>
          )}
          {filtered.length > shown.length && (
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, p: 1, flexWrap: 'wrap' }}>
              <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                {shown.length.toLocaleString('de-DE')} von{' '}
                {filtered.length.toLocaleString('de-DE')} angezeigt
              </Typography>
              <Button size="small" onClick={() => setLimit((prev) => prev + PAGE)}>
                Weitere anzeigen
              </Button>
            </Box>
          )}
        </Box>
      </Box>
      {manualEntry}
    </Box>
  )

  const chosen = (
    <Box sx={{ minWidth: 0 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', minHeight: 32, gap: 1 }}>
        <Typography component="h4" sx={{ fontSize: 14, fontWeight: 600, flex: 1 }}>
          Ausgewählt ({selected.length.toLocaleString('de-DE')})
        </Typography>
        {selected.length > 1 && (
          <Button size="small" onClick={() => remove(new Set(selectedKeys))}>
            Alle entfernen
          </Button>
        )}
      </Box>
      {selected.length === 0 ? (
        <Typography sx={{ fontSize: 13, color: 'text.secondary', py: 1 }}>
          {emptySelection}
        </Typography>
      ) : (
        <Box sx={{ ...listBoxSx, px: 1.5 }}>
          <PickedList
            items={selected}
            rootLabel={rootLabel}
            problems={problems}
            onRemove={(item) => remove(new Set([item.key]))}
          />
        </Box>
      )}
    </Box>
  )

  return (
    <Box
      ref={ref}
      component="section"
      aria-labelledby={headingId}
      data-source-field="selection"
      tabIndex={-1}
      sx={{ minWidth: 0, outline: 'none' }}
    >
      <Typography id={headingId} component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1 }}>
        {title}
      </Typography>
      {issueLine}
      {split ? (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: 'minmax(0, 3fr) minmax(0, 2fr)',
            gap: 3,
          }}
        >
          {browse}
          <Box sx={{ borderLeft: 1, borderColor: 'divider', pl: 3 }}>{chosen}</Box>
        </Box>
      ) : (
        <Box>
          <Tabs
            value={view}
            onChange={(_, next) => setView(next)}
            aria-label={`${title}: Ansicht`}
            sx={{ mb: 1.5, minHeight: 40 }}
          >
            <Tab value="browse" label="Durchsuchen" sx={{ minHeight: 40 }} />
            <Tab
              value="selected"
              label={`Ausgewählt (${selected.length.toLocaleString('de-DE')})`}
              sx={{ minHeight: 40 }}
            />
          </Tabs>
          {view === 'browse' ? browse : chosen}
        </Box>
      )}
      {status}
    </Box>
  )
}

/** The issues of the selection under its title, in the danger colour like a field's message. */
export function SelectionIssues({ messages }: { messages: string[] }) {
  return (
    <Box sx={{ mb: 1 }}>
      {messages.map((message) => (
        <Typography key={message} sx={{ fontSize: 12.5, color: 'error.main' }}>
          {message}
        </Typography>
      ))}
    </Box>
  )
}

function EntryRow({
  idPrefix,
  entry,
  checked,
  mixed,
  coveredBy,
  onToggle,
  onOpen,
}: {
  idPrefix: string
  entry: PickerEntry
  checked: boolean
  mixed: boolean
  coveredBy: string | null
  onToggle: () => void
  onOpen: () => void
}) {
  const id = `${idPrefix}-entry-${entry.key.replace(/[^a-zA-Z0-9_-]/g, '_')}`
  const name = (
    <Tooltip title={entry.name} placement="top-start" enterDelay={600}>
      <Box component="span" sx={{ display: 'block', minWidth: 0 }}>
        <Box component="span" sx={nameSx}>
          {entry.name}
        </Box>
        {(coveredBy || entry.hint) && (
          <Box component="span" sx={{ ...nameSx, fontSize: 12, color: 'text.secondary' }}>
            {coveredBy ? `enthalten in „${coveredBy}“` : entry.hint}
          </Box>
        )}
      </Box>
    </Tooltip>
  )
  if (!entry.selectable) {
    // a level only leads further down: the whole row opens it
    return (
      <Box component="li">
        <ButtonBase
          onClick={onOpen}
          sx={{ ...rowSx, width: '100%', textAlign: 'left', pl: 1.5 }}
          aria-label={entry.name}
        >
          <Box sx={{ flex: 1, minWidth: 0 }}>{name}</Box>
          <ChevronRightIcon aria-hidden fontSize="small" sx={{ color: 'text.secondary' }} />
        </ButtonBase>
      </Box>
    )
  }
  return (
    <Box component="li" sx={rowSx}>
      <Checkbox
        id={id}
        size="small"
        checked={checked || coveredBy !== null}
        indeterminate={mixed}
        disabled={coveredBy !== null}
        onChange={onToggle}
        slotProps={{
          input: {
            'aria-label': entry.name,
            ...(mixed ? { 'aria-checked': 'mixed' as const } : {}),
          },
        }}
      />
      <Box
        component="label"
        htmlFor={id}
        sx={{ flex: 1, minWidth: 0, cursor: coveredBy ? 'default' : 'pointer', py: 0.5 }}
      >
        {name}
      </Box>
      {entry.hasChildren && (
        <Tooltip title="Öffnen">
          <IconButton size="small" aria-label={`${entry.name} öffnen`} onClick={onOpen}>
            <ChevronRightIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      )}
    </Box>
  )
}

function ParentColumn({
  loaded,
  activeKey,
  onOpen,
}: {
  loaded: Loaded | undefined
  activeKey: string
  onOpen: (entry: PickerEntry) => void
}) {
  const entries = loaded?.status === 'ok' ? loaded.level.entries : []
  return (
    <Box sx={{ ...listBoxSx, bgcolor: 'background.paper' }}>
      <Box component="ul" aria-label="Übergeordnete Ebene" sx={{ listStyle: 'none', m: 0, p: 0 }}>
        {entries.slice(0, PAGE).map((entry) => {
          const active = entry.key === activeKey
          return (
            <Box component="li" key={entry.key}>
              <ButtonBase
                onClick={() => onOpen(entry)}
                aria-current={active ? 'true' : undefined}
                sx={{
                  ...rowSx,
                  width: '100%',
                  textAlign: 'left',
                  pl: 1.5,
                  fontWeight: active ? 600 : 400,
                  bgcolor: active ? 'action.selected' : undefined,
                }}
              >
                <Box component="span" sx={{ ...nameSx, flex: 1 }} title={entry.name}>
                  {entry.name}
                </Box>
                {entry.hasChildren && (
                  <ChevronRightIcon aria-hidden fontSize="small" sx={{ color: 'text.secondary' }} />
                )}
              </ButtonBase>
            </Box>
          )
        })}
      </Box>
    </Box>
  )
}

const rowSx = {
  display: 'flex',
  alignItems: 'center',
  gap: 0.5,
  minHeight: 40,
  pr: 0.5,
  borderBottom: 1,
  borderColor: 'divider',
  '&:hover': { bgcolor: 'action.hover' },
} as const

const nameSx = {
  display: 'block',
  fontSize: 13.5,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
} as const

const crumbSx = {
  display: 'inline-block',
  maxWidth: '16ch',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
} as const

const listBoxSx = {
  border: 1,
  borderColor: 'divider',
  borderRadius: 1.25,
  maxHeight: 400,
  overflowY: 'auto',
} as const

const visuallyHidden = {
  position: 'absolute',
  width: 1,
  height: 1,
  overflow: 'hidden',
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap',
} as const
