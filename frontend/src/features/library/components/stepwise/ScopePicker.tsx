import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Chip from '@mui/material/Chip'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Skeleton from '@mui/material/Skeleton'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import ChevronRight from '@mui/icons-material/ChevronRight'
import ExpandMore from '@mui/icons-material/ExpandMore'
import SearchIcon from '@mui/icons-material/Search'
import CloseIcon from '@mui/icons-material/Close'
import { apiErrorStatus } from '@/shared/api/apiErrorDetails'
import type { ScopeNode, StepContext, StepwiseScope } from './types'

const ROOT = '__root__'
const PAGE = 50
/** From this many entries on a level the search field appears (checklist #20). */
const SEARCH_FROM = 7
/** Loading shorter than this shows nothing, so a fast answer does not flicker (checklist #39). */
const LOADING_DELAY_MS = 300

interface LevelState {
  status: 'loading' | 'done' | 'error'
  nodes: ScopeNode[]
  complete: boolean
  message?: string | null
  /** HTTP 403: the listing is refused, not broken. */
  denied?: boolean
  startedAt: number
}

interface ScopePickerProps<V> {
  scope: StepwiseScope<V>
  values: V
  onChange: (patch: Partial<V>) => void
  ctx: StepContext
  idPrefix: string
  /** The field error of the selection, shown above the list and linked from the summary. */
  error?: string
  /** Selected entries the last check could not read, by id. */
  failedEntries?: Record<string, string>
}

/**
 * The one selection of foreign objects of a source (Ordner, Spaces, Buckets, Sites): a list, or a
 * tree that loads each level when it is opened. Search from seven entries on, „Alle n auswählen“
 * where the whole set fits, a parent with chosen children shows „teilweise“ (tri-state), and the
 * selection stays visible above the list as „n ausgewählt“, whatever the filter shows.
 */
export default function ScopePicker<V>({
  scope,
  values,
  onChange,
  ctx,
  idPrefix,
  error,
  failedEntries = {},
}: ScopePickerProps<V>) {
  const [levels, setLevels] = useState<Record<string, LevelState>>({})
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [pages, setPages] = useState<Record<string, number>>({})
  const [query, setQuery] = useState('')
  const [limitHit, setLimitHit] = useState(false)
  const [showAllChosen, setShowAllChosen] = useState(false)
  const [, setTick] = useState(0)
  const valuesRef = useRef(values)
  valuesRef.current = values
  const [plainSingular, plural] = scope.noun

  const whole = scope.whole?.isWhole(values) ?? false
  const selected = scope.selection(values)
  const selectedIds = useMemo(() => new Set(selected.map((node) => node.id)), [selected])
  const partlyIds = useMemo(() => {
    const ids = new Set<string>()
    for (const node of selected) for (const ancestor of node.ancestors) ids.add(ancestor)
    return ids
  }, [selected])

  const load = useCallback(
    async (parent: ScopeNode | null) => {
      const key = parent?.id ?? ROOT
      setLevels((prev) => ({
        ...prev,
        [key]: { status: 'loading', nodes: [], complete: true, startedAt: Date.now() },
      }))
      // after the delay a still running level shows its skeleton
      window.setTimeout(() => setTick((t) => t + 1), LOADING_DELAY_MS + 20)
      try {
        const listing = await scope.load(parent, valuesRef.current, ctx)
        setLevels((prev) => ({
          ...prev,
          [key]: { status: 'done', ...listing, startedAt: 0 },
        }))
        if (!parent && listing.nodes.length === 1 && listing.nodes[0].expandable) {
          // one site, one bucket: opened right away, nothing to choose on this level
          setExpanded((prev) => new Set(prev).add(listing.nodes[0].id))
          void loadChild(listing.nodes[0])
        }
      } catch (err) {
        setLevels((prev) => ({
          ...prev,
          [key]: {
            status: 'error',
            nodes: [],
            complete: false,
            message: err instanceof Error ? err.message : null,
            denied: apiErrorStatus(err) === 403,
            startedAt: 0,
          },
        }))
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [scope, ctx.sourceType],
  )
  const loadChild = (node: ScopeNode) => load(node)

  useEffect(() => {
    if (whole) return
    if (levels[ROOT]) return
    void load(null)
  }, [whole, levels, load])

  function toggleExpanded(node: ScopeNode) {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(node.id)) next.delete(node.id)
      else next.add(node.id)
      return next
    })
    if (!levels[node.id] || levels[node.id].status === 'error') void load(node)
  }

  function setSelection(nodes: ScopeNode[]) {
    onChange(scope.withSelection(values, nodes))
  }

  function toggle(node: ScopeNode) {
    if (selectedIds.has(node.id)) {
      setLimitHit(false)
      setSelection(selected.filter((s) => s.id !== node.id))
      return
    }
    // a chosen parent covers its children; they leave the selection
    const kept = selected.filter((s) => !s.ancestors.includes(node.id))
    if (counted([...kept, node]) > scope.max) {
      setLimitHit(true)
      return
    }
    setLimitHit(false)
    setSelection([...kept, node])
  }

  function chooseAll(nodes: ScopeNode[]) {
    const fresh = nodes.filter((n) => n.selectable && !selectedIds.has(n.id) && !covered(n))
    setSelection([...selected, ...fresh])
  }

  const counted = (nodes: ScopeNode[]) => scope.countForMax?.(nodes) ?? nodes.length
  const covered = (node: ScopeNode) => node.ancestors.some((id) => selectedIds.has(id))

  const allLoaded = useMemo(() => Object.values(levels).flatMap((level) => level.nodes), [levels])
  const root = levels[ROOT]
  const trimmed = query.trim().toLowerCase()
  const hits = trimmed
    ? allLoaded.filter((node) => node.name.toLowerCase().includes(trimmed))
    : null
  const showSearch = (root?.nodes.length ?? 0) >= SEARCH_FROM || trimmed !== ''

  const listLabelId = `${idPrefix}-scope-list-label`

  function chooseAllButton(nodes: ScopeNode[], filtered: boolean) {
    const candidates = nodes.filter((n) => n.selectable && !selectedIds.has(n.id) && !covered(n))
    if (candidates.length < 2) return null
    if (counted([...selected, ...candidates]) > scope.max) return null
    const label = filtered
      ? `Alle ${candidates.length} Treffer auswählen`
      : `Alle ${candidates.length} auswählen`
    return (
      <Button variant="text" size="small" onClick={() => chooseAll(candidates)}>
        {label}
      </Button>
    )
  }

  function renderRow(node: ScopeNode, depth: number, withTrail: boolean) {
    const isSelected = selectedIds.has(node.id)
    const isCovered = !isSelected && covered(node)
    const partly = !isSelected && !isCovered && partlyIds.has(node.id)
    const open = expanded.has(node.id)
    const failure = failedEntries[node.id]
    const inputId = `${idPrefix}-node-${node.id}`
    const nameLabel = (
      <Box sx={{ minWidth: 0 }}>
        <Tooltip title={node.name.length > 40 ? node.name : ''} placement="top-start">
          <Typography
            component="span"
            sx={{
              display: 'block',
              fontSize: 14,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {node.name}
          </Typography>
        </Tooltip>
        {(withTrail && node.trail.length > 0) || node.meta || isCovered || failure ? (
          <Typography
            component="span"
            sx={{
              display: 'block',
              fontSize: 12.5,
              color: failure ? 'error.main' : 'text.secondary',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {failure
              ? `Nicht lesbar: ${failure}`
              : isCovered
                ? 'Mit dem übergeordneten Eintrag gewählt'
                : withTrail && node.trail.length > 0
                  ? node.trail.join(' › ')
                  : node.meta}
          </Typography>
        ) : null}
      </Box>
    )
    return (
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          minHeight: 40,
          pl: { xs: `${Math.min(depth, 4) * 12}px`, sm: `${Math.min(depth, 8) * 24}px` },
          borderBottom: 1,
          borderColor: 'divider',
          '&:hover': { bgcolor: 'action.hover' },
        }}
      >
        {scope.hierarchical && !withTrail && (
          <Box sx={{ width: 32, flex: 'none', display: 'flex', justifyContent: 'center' }}>
            {node.expandable && (
              <Tooltip title={open ? 'Zuklappen' : 'Aufklappen'}>
                <IconButton
                  size="small"
                  aria-expanded={open}
                  aria-label={`${node.name} ${open ? 'zuklappen' : 'aufklappen'}`}
                  onClick={() => toggleExpanded(node)}
                  sx={{ width: 28, height: 28 }}
                >
                  {open ? <ExpandMore fontSize="small" /> : <ChevronRight fontSize="small" />}
                </IconButton>
              </Tooltip>
            )}
          </Box>
        )}
        {node.selectable ? (
          <FormControlLabel
            sx={{ m: 0, minWidth: 0, flex: 1, py: 0.25, pr: 1 }}
            control={
              <Checkbox
                id={inputId}
                size="small"
                checked={isSelected || isCovered}
                indeterminate={partly}
                disabled={isCovered}
                onChange={() => toggle(node)}
                slotProps={{
                  input: {
                    'aria-checked': partly ? 'mixed' : isSelected || isCovered,
                    'aria-invalid': failure ? true : undefined,
                  } as Record<string, unknown>,
                }}
              />
            }
            label={nameLabel}
            disableTypography
          />
        ) : (
          <Box sx={{ minWidth: 0, flex: 1, py: 0.75, pl: 1.25, pr: 1 }}>{nameLabel}</Box>
        )}
      </Box>
    )
  }

  function renderLevel(parent: ScopeNode | null, depth: number) {
    const key = parent?.id ?? ROOT
    const level = levels[key]
    if (!level) return null
    if (level.status === 'loading') {
      const slow = Date.now() - level.startedAt >= LOADING_DELAY_MS
      return (
        <Box aria-busy="true" sx={{ pl: { xs: `${depth * 12}px`, sm: `${depth * 24}px` } }}>
          <Box component="span" role="status" sx={visuallyHidden}>
            {slow ? `${plural} werden geladen` : ''}
          </Box>
          {slow &&
            [0, 1, 2].map((i) => (
              <Skeleton
                key={i}
                variant="rounded"
                height={28}
                sx={{ my: 0.75, maxWidth: 360 - i * 60 }}
              />
            ))}
        </Box>
      )
    }
    if (level.status === 'error') {
      return (
        <Box sx={{ py: 1, pl: { xs: `${depth * 12}px`, sm: `${depth * 24 + 32}px` } }}>
          <Typography sx={{ fontSize: 13.5 }}>
            {level.denied
              ? `Kein Zugriff auf die Liste der ${plural}.`
              : `Die ${plural} konnten nicht geladen werden.`}
          </Typography>
          {level.message && (
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', overflowWrap: 'anywhere' }}>
              {level.message}
            </Typography>
          )}
          <Button variant="text" size="small" onClick={() => void load(parent)} sx={{ mt: 0.5 }}>
            Erneut versuchen
          </Button>
        </Box>
      )
    }
    if (level.nodes.length === 0) {
      return (
        <Box sx={{ py: 1, pl: { xs: `${depth * 12}px`, sm: `${depth * 24 + 32}px` } }}>
          <Typography sx={{ fontSize: 13.5, color: parent ? 'text.secondary' : 'text.primary' }}>
            {parent
              ? `Keine weiteren ${plural}.`
              : level.complete
                ? `In der Quelle sind keine ${plural} sichtbar.`
                : `Die Quelle hat keine ${plural} aufgelistet.`}
          </Typography>
          {!parent && (
            <Button variant="text" size="small" onClick={() => void load(null)}>
              Erneut laden
            </Button>
          )}
        </Box>
      )
    }
    const shown = pages[key] ?? PAGE
    const rest = level.nodes.length - shown
    return (
      <>
        {!level.complete && level.message && (
          <Typography
            sx={{
              fontSize: 12.5,
              color: 'text.secondary',
              py: 0.75,
              pl: { xs: `${depth * 12}px`, sm: `${depth * 24 + 32}px` },
            }}
          >
            Unvollständig: {level.message}
          </Typography>
        )}
        <Box component="ul" role="list" sx={{ listStyle: 'none', m: 0, p: 0 }}>
          {level.nodes.slice(0, shown).map((node) => (
            <Box component="li" key={node.id}>
              {renderRow(node, depth, false)}
              {expanded.has(node.id) && renderLevel(node, depth + 1)}
            </Box>
          ))}
        </Box>
        {rest > 0 && (
          <Box sx={{ pl: { xs: `${depth * 12}px`, sm: `${depth * 24 + 32}px` }, py: 0.5 }}>
            <Button
              variant="text"
              size="small"
              onClick={() => setPages((prev) => ({ ...prev, [key]: shown + PAGE }))}
            >
              {`Weitere ${Math.min(PAGE, rest)} von ${rest} anzeigen`}
            </Button>
          </Box>
        )}
      </>
    )
  }

  // a short flat list shows its selection itself; chips help where it may scroll or hide
  const showChips = scope.hierarchical || (root?.nodes.length ?? 0) >= SEARCH_FROM || trimmed !== ''
  const chosenShown = showAllChosen ? selected : selected.slice(0, 6)
  const Manual = scope.Manual
  const Extra = scope.Extra
  const rootRefused = root?.status === 'error' || (root?.status === 'done' && !root.complete)

  return (
    <Box sx={{ maxWidth: 720 }}>
      {Extra && <Extra values={values} onChange={onChange} idPrefix={idPrefix} />}
      {scope.whole && (
        <RadioGroup
          aria-label={`Umfang: ${plural}`}
          value={whole ? 'whole' : 'part'}
          onChange={(event) => onChange(scope.whole!.set(values, event.target.value === 'whole'))}
          sx={{ mb: 2 }}
        >
          <FormControlLabel
            value="whole"
            control={<Radio size="small" />}
            label={scope.whole.label}
          />
          <FormControlLabel
            value="part"
            control={<Radio size="small" />}
            label={scope.whole.partLabel}
          />
        </RadioGroup>
      )}

      {!whole && (
        <>
          <Box
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 1,
              flexWrap: 'wrap',
              minHeight: 36,
              mb: 1,
            }}
            aria-live="polite"
          >
            <Typography id={listLabelId} sx={{ fontSize: 14, fontWeight: 600 }}>
              {selected.length === 0
                ? `Keine ${plural} ausgewählt`
                : `${selected.length} ausgewählt`}
            </Typography>
            {counted(selected) >= scope.max - 10 && (
              <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                {`höchstens ${scope.max} ${plural}`}
              </Typography>
            )}
            {selected.length > 0 && (
              <Button
                variant="text"
                size="small"
                onClick={() => setSelection([])}
                sx={{ ml: 'auto' }}
              >
                Auswahl aufheben
              </Button>
            )}
          </Box>
          {selected.length > 0 && showChips && (
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, mb: 2 }}>
              {chosenShown.map((node) => (
                <Tooltip key={node.id} title={[...node.trail, node.name].join(' › ')}>
                  <Chip
                    size="small"
                    variant="outlined"
                    label={
                      node.trail.length > 0
                        ? `${node.trail[node.trail.length - 1]} › ${node.name}`
                        : node.name
                    }
                    onDelete={() => toggle(node)}
                    deleteIcon={<CloseIcon aria-label={`${node.name} entfernen`} />}
                    sx={{ maxWidth: { xs: 260, sm: 320 } }}
                  />
                </Tooltip>
              ))}
              {selected.length > 6 && (
                <Button variant="text" size="small" onClick={() => setShowAllChosen((s) => !s)}>
                  {showAllChosen ? 'Weniger zeigen' : `und ${selected.length - 6} weitere`}
                </Button>
              )}
            </Box>
          )}
          {(error || limitHit) && (
            <Typography
              id={`${idPrefix}-scope-error`}
              sx={{ fontSize: 12.5, color: 'error.main', mb: 1 }}
            >
              {limitHit ? `Höchstens ${scope.max} ${plural} je Bibliothek.` : error}
            </Typography>
          )}

          {showSearch && (
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap', mb: 1 }}>
              <TextField
                size="small"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Suchen"
                sx={{ flex: '1 1 220px', maxWidth: 360 }}
                slotProps={{
                  htmlInput: { 'aria-label': `${plural} durchsuchen` },
                  input: {
                    startAdornment: (
                      <InputAdornment position="start">
                        <SearchIcon fontSize="small" aria-hidden />
                      </InputAdornment>
                    ),
                  },
                }}
              />
              {hits && (
                <Typography
                  sx={{ fontSize: 12.5, color: 'text.secondary' }}
                >{`${hits.length} Treffer`}</Typography>
              )}
              {!hits &&
                !scope.hierarchical &&
                root?.status === 'done' &&
                chooseAllButton(root.nodes, false)}
              {hits && chooseAllButton(hits, true)}
            </Box>
          )}
          {!showSearch && !scope.hierarchical && root?.status === 'done' && (
            <Box sx={{ mb: 0.5 }}>{chooseAllButton(root.nodes, false)}</Box>
          )}

          <Box
            role="group"
            aria-labelledby={listLabelId}
            aria-describedby={error ? `${idPrefix}-scope-error` : undefined}
            id={`${idPrefix}-field-scope`}
            tabIndex={-1}
            sx={{ borderTop: 1, borderColor: error ? 'error.main' : 'divider', outline: 'none' }}
          >
            {hits ? (
              hits.length === 0 ? (
                <Box sx={{ py: 1.5 }}>
                  <Typography
                    sx={{ fontSize: 13.5 }}
                  >{`Keine Treffer für „${query.trim()}“.`}</Typography>
                  <Button variant="text" size="small" onClick={() => setQuery('')}>
                    Suche zurücksetzen
                  </Button>
                </Box>
              ) : (
                <Box component="ul" role="list" sx={{ listStyle: 'none', m: 0, p: 0 }}>
                  {hits.slice(0, pages.__hits ?? PAGE).map((node) => (
                    <Box component="li" key={node.id}>
                      {renderRow(node, 0, true)}
                    </Box>
                  ))}
                  {hits.length > (pages.__hits ?? PAGE) && (
                    <Button
                      variant="text"
                      size="small"
                      onClick={() =>
                        setPages((prev) => ({ ...prev, __hits: (prev.__hits ?? PAGE) + PAGE }))
                      }
                    >
                      {`Weitere Treffer anzeigen`}
                    </Button>
                  )}
                </Box>
              )
            ) : (
              renderLevel(null, 0)
            )}
          </Box>
          {hits && scope.hierarchical && (
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
              Gesucht wird in den bereits geöffneten Ebenen.
            </Typography>
          )}
          {Manual && (
            <ManualEntry open={rootRefused} singular={plainSingular}>
              <Manual values={values} onChange={onChange} idPrefix={idPrefix} />
            </ManualEntry>
          )}
        </>
      )}
    </Box>
  )
}

function ManualEntry({
  open,
  singular,
  children,
}: {
  open: boolean
  singular: string
  children: React.ReactNode
}) {
  const [shown, setShown] = useState(false)
  const visible = open || shown
  return (
    <Box sx={{ mt: 2 }}>
      {!open && (
        <Button
          variant="text"
          size="small"
          aria-expanded={visible}
          onClick={() => setShown((s) => !s)}
        >
          {`${singular} von Hand angeben`}
        </Button>
      )}
      {visible && <Box sx={{ mt: 1 }}>{children}</Box>}
    </Box>
  )
}
