import { useEffect, useMemo, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import Skeleton from '@mui/material/Skeleton'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import CloseIcon from '@mui/icons-material/Close'
import { confirmAction } from '@/shared/stores/confirmStore'
import { fontFamily } from '@/shared/theme/tokens'
import { incompleteNote } from './incompleteNote'
import { plainMessage } from './plainMessage'
import PathLabel from '../setup/PathLabel'
import { TextAction } from '@/shared/ui/DetailSection'
import {
  adoptTrail,
  isBelow,
  noun,
  pathLabel,
  toggle,
  type BrowseResult,
  type Crumb,
  type SelectionItem,
  type SelectionSource,
} from './selection'

/** A level shows its search field from this many entries on (Prüfliste #35). */
const SEARCH_FROM = 7
/** Rows rendered at once; the rest follows on demand so 600 buckets stay responsive. */
const PAGE = 100
/** Answers faster than this show no loading state at all (Prüfliste #39). */
const LOADING_DELAY = 300
/** Stored entries outside the opened level shown ticked above the list; the rest in „Auswahl ansehen“. */
const PINNED_SHOWN = 5

interface Listing {
  /** The level and server-side search the answer belongs to. */
  token: string
  result?: BrowseResult
  error?: string
}

interface SelectionDialogProps {
  open: boolean
  title: string
  source: SelectionSource
  value: SelectionItem[]
  onApply: (items: SelectionItem[]) => void
  onClose: () => void
  /** The level the dialog opens at, e.g. the SharePoint site of the stored selection. */
  initialLevel?: Crumb[]
  /** Opens with the field for typing an object by hand, e.g. after a refused listing. */
  startManual?: boolean
}

/** The level an entry opens; a placeholder name stays marked, so it is never stored. */
function crumbOf(entry: { key: string; name: string; placeholder?: boolean }): Crumb {
  return entry.placeholder
    ? { key: entry.key, name: entry.name, placeholder: true }
    : { key: entry.key, name: entry.name }
}

const ellipsis = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  minWidth: 0,
} as const

/**
 * The one selection dialog of every source (pattern of a file picker): a breadcrumb over
 * the opened levels, a search within the current level, a list with checkboxes - a parent with
 * chosen objects below it shows the mixed state -, „Alle n auswählen“ for the listed rows and a
 * footer with the count and „Übernehmen“. The dialog works on a copy; only „Übernehmen“ hands it
 * back, closing with changes asks first.
 */
export default function SelectionDialog(props: SelectionDialogProps) {
  // a fresh instance per opening starts from the current selection at the outermost level
  return props.open ? <SelectionDialogBody {...props} /> : null
}

function SelectionDialogBody({
  title,
  source,
  value,
  onApply,
  onClose,
  initialLevel = [],
  startManual = false,
}: SelectionDialogProps) {
  const theme = useTheme()
  const narrow = useMediaQuery(theme.breakpoints.down('sm'))
  const [draft, setDraft] = useState<SelectionItem[]>(value)
  const [level, setLevel] = useState<Crumb[]>(initialLevel)
  const [search, setSearch] = useState('')
  const [serverQuery, setServerQuery] = useState('')
  const [listing, setListing] = useState<Listing | null>(null)
  const [showLoading, setShowLoading] = useState(false)
  const [shown, setShown] = useState(PAGE)
  const [reviewing, setReviewing] = useState(false)
  const [manualOpen, setManualOpen] = useState(startManual)
  const [manualText, setManualText] = useState('')
  const [manualError, setManualError] = useState<string | null>(null)
  const [applyError, setApplyError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const listRef = useRef<HTMLDivElement>(null)

  const serverSearch = source.serverSearch(level)
  const token = JSON.stringify({
    level: level.map((c) => c.key),
    q: serverSearch ? serverQuery : '',
    attempt,
  })
  const current = listing?.token === token ? listing : null
  const loading = current === null

  // the server searches after a pause in typing, never on every key
  useEffect(() => {
    if (!serverSearch) return
    const timer = setTimeout(() => setServerQuery(search.trim()), 300)
    return () => clearTimeout(timer)
  }, [search, serverSearch])

  useEffect(() => {
    let alive = true
    const delay = setTimeout(() => alive && setShowLoading(true), LOADING_DELAY)
    source
      .list(level, serverSearch ? serverQuery : '')
      .then((result) => {
        if (!alive) return
        setListing({ token, result })
        if (source.adoptsTrail) setDraft((prev) => adoptTrail(prev, result.entries, level))
      })
      .catch(
        (err: unknown) =>
          alive &&
          setListing({
            token,
            error: err instanceof Error ? err.message : 'Die Liste konnte nicht geladen werden.',
          }),
      )
      .finally(() => {
        clearTimeout(delay)
        if (alive) setShowLoading(false)
      })
    return () => {
      alive = false
      clearTimeout(delay)
    }
    // the token names everything the request depends on
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token])

  const entries = useMemo(() => {
    const all = current?.result?.entries ?? []
    const term = search.trim().toLowerCase()
    if (serverSearch || term === '') return all
    return all.filter((entry) => entry.name.toLowerCase().includes(term))
  }, [current, search, serverSearch])
  const levelSize = current?.result?.entries.length ?? 0
  const filtered = search.trim() !== ''
  const showSearch = filtered || (levelSize > 0 && (serverSearch || levelSize >= SEARCH_FROM))

  const chosenKeys = useMemo(() => new Set(draft.map((item) => item.key)), [draft])
  const coveringParent = level.find((crumb) => chosenKeys.has(crumb.key))
  const selectable = coveringParent
    ? []
    : entries.filter((entry) => entry.selectable && !entry.blocked)
  const selectableChosen = selectable.filter((entry) => chosenKeys.has(entry.key)).length
  const dirty = draft.length !== value.length || draft.some((item, i) => item.key !== value[i]?.key)

  function openLevel(next: Crumb[]) {
    setLevel(next)
    setSearch('')
    setServerQuery('')
    setShown(PAGE)
    setManualError(null)
    listRef.current?.scrollTo?.({ top: 0 })
  }

  function switchAll() {
    if (selectableChosen === selectable.length) {
      const keys = new Set(selectable.map((entry) => entry.key))
      setDraft((prev) => prev.filter((item) => !keys.has(item.key)))
    } else {
      setDraft((prev) =>
        selectable.reduce(
          (acc, entry) =>
            acc.some((item) => item.key === entry.key) ? acc : toggle(acc, entry, level),
          prev,
        ),
      )
    }
    setApplyError(null)
  }

  async function requestClose() {
    if (dirty) {
      const discard = await confirmAction({
        question: 'Geänderte Auswahl verwerfen?',
        consequence: 'Die Auswahl bleibt, wie sie vor dem Öffnen war.',
        confirmLabel: 'Verwerfen',
        tone: 'neutral',
      })
      if (!discard) return
    }
    onClose()
  }

  function apply() {
    if (source.max !== undefined && draft.length > source.max) {
      setApplyError(`Höchstens ${source.max} ${noun(source, source.max)} je Bibliothek.`)
      return
    }
    onApply(draft)
    onClose()
  }

  async function addManual() {
    const text = manualText.trim()
    if (text === '') {
      setManualError(`Bitte ${source.manual?.label ?? 'einen Wert'} eingeben.`)
      return
    }
    const resolved = await source.manual!.resolve(text, level)
    if (typeof resolved === 'string') {
      setManualError(resolved)
      return
    }
    setManualError(null)
    setManualText('')
    if (source.manual!.kind === 'open') {
      openLevel([...resolved.trail, crumbOf(resolved)])
    } else if (!chosenKeys.has(resolved.key)) {
      setDraft((prev) => toggle(prev, resolved, resolved.trail))
    }
  }

  const levelName = level.length ? level[level.length - 1].name : source.rootLabel
  const someBlocked = entries.some((entry) => entry.blocked)
  const allLabel =
    current?.result && !current.result.complete
      ? 'Alle geladenen auswählen'
      : filtered
        ? `Alle ${selectable.length} Treffer auswählen`
        : someBlocked
          ? `Alle ${selectable.length} wählbaren auswählen`
          : `Alle ${selectable.length} auswählen`
  const levelNoun = source.levelNoun?.(level) ?? source.noun[1]
  // the stored selection that lies outside the opened level stays visible, ticked, above the list
  const pinned =
    !filtered && current?.result
      ? draft.filter(
          (item) =>
            value.some((stored) => stored.key === item.key) &&
            !entries.some((entry) => entry.key === item.key) &&
            !level.some(
              (crumb) => crumb.key === item.key || isBelow({ ...item, trail: level }, crumb.key),
            ) &&
            level.every((crumb, i) => item.trail[i]?.key === crumb.key),
        )
      : []

  function rowState(key: string): { checked: boolean; mixed: boolean } {
    if (chosenKeys.has(key)) return { checked: true, mixed: false }
    return { checked: false, mixed: draft.some((item) => isBelow(item, key)) }
  }

  const body = reviewing ? (
    <Box>
      {draft.length === 0 ? (
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', py: 2 }}>
          {source.emptyMeans ?? `Noch keine ${source.noun[1]} ausgewählt.`}
        </Typography>
      ) : (
        <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }} aria-label="Ausgewählt">
          {draft.map((item) => (
            <Box
              component="li"
              key={item.key}
              sx={{
                display: 'flex',
                alignItems: 'center',
                gap: 1,
                py: 0.5,
                borderBottom: 1,
                borderColor: 'divider',
              }}
            >
              <Tooltip disableInteractive title={pathLabel(item)} placement="bottom-start">
                <Typography sx={{ ...ellipsis, flex: 1, fontSize: 14, color: 'text.primary' }}>
                  {item.name}
                  {item.trail.length > 0 && (
                    <Box component="span" sx={{ color: 'text.secondary' }}>
                      {' · in '}
                      {item.trail.map((crumb) => crumb.name).join(' / ')}
                    </Box>
                  )}
                </Typography>
              </Tooltip>
              <Tooltip disableInteractive title="Entfernen">
                <IconButton
                  size="small"
                  aria-label={`„${item.name}“ entfernen`}
                  onClick={() => setDraft((prev) => prev.filter((other) => other.key !== item.key))}
                >
                  <CloseIcon fontSize="small" />
                </IconButton>
              </Tooltip>
            </Box>
          ))}
        </Box>
      )}
    </Box>
  ) : (
    <Box>
      {current?.result && incompleteNote(current.result) && (
        <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 1 }}>
          {incompleteNote(current.result)}
        </Typography>
      )}
      {loading && showLoading && (
        <Box role="status" aria-busy="true" aria-label={`${levelName} wird geladen`}>
          {[0, 1, 2, 3, 4].map((i) => (
            <Skeleton key={i} variant="rounded" height={32} sx={{ my: 0.5 }} />
          ))}
        </Box>
      )}
      {current?.error && (
        <Box sx={{ py: 1 }}>
          <Typography sx={{ fontSize: 14, fontWeight: 500, color: 'text.primary' }}>
            {/^.*(403|verweiger|Berechtigung|AccessDenied)/i.test(current.error)
              ? `Kein Zugriff auf „${levelName}“.`
              : `„${levelName}“ ließ sich nicht laden.`}
          </Typography>
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 0.5 }}>
            {plainMessage(current.error)}
          </Typography>
          <TextAction onClick={() => setAttempt((n) => n + 1)} sx={{ mt: 0.5 }}>
            Erneut laden
          </TextAction>
        </Box>
      )}
      {current?.result && levelSize === 0 && !filtered && (
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', py: 1 }}>
          {current.result.complete
            ? `In „${levelName}“ liegen keine ${levelNoun}.`
            : current.result.message
              ? null
              : 'Die Quelle hat für diese Ebene nichts geliefert.'}
        </Typography>
      )}
      {current?.result && entries.length === 0 && filtered && (
        <Box sx={{ py: 1 }}>
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Keine Treffer für „{search.trim()}“.
          </Typography>
          <TextAction onClick={() => setSearch('')} sx={{ mt: 0.5 }}>
            Suche zurücksetzen
          </TextAction>
        </Box>
      )}
      {someBlocked && !coveringParent && (
        <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 1 }}>
          {entries.find((entry) => entry.blocked)?.blocked}
        </Typography>
      )}
      {pinned.length > 0 && (
        <Box role="group" aria-label="Bereits ausgewählt">
          {pinned.slice(0, PINNED_SHOWN).map((item) => (
            <Box
              key={item.key}
              sx={{
                display: 'flex',
                alignItems: 'center',
                gap: 0.5,
                minHeight: 40,
                borderBottom: 1,
                borderColor: 'divider',
              }}
            >
              <Checkbox
                size="small"
                checked
                onChange={() => setDraft((prev) => prev.filter((other) => other.key !== item.key))}
                slotProps={{ input: { 'aria-label': pathLabel(item) } }}
              />
              <Box sx={{ flex: 1, minWidth: 0, fontSize: 14 }}>
                <PathLabel path={pathLabel(item)} />
              </Box>
            </Box>
          ))}
          {pinned.length > PINNED_SHOWN && (
            <Typography sx={{ fontSize: 13, color: 'text.secondary', py: 1 }}>
              und {pinned.length - PINNED_SHOWN} weitere ausgewählt
            </Typography>
          )}
        </Box>
      )}
      {coveringParent && entries.length > 0 && (
        <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 1 }}>
          Ganz ausgewählt über „{coveringParent.name}“.
        </Typography>
      )}
      {entries.length > 0 && (
        <Box role="group" aria-label={`Inhalt von ${levelName}`}>
          {selectable.length >= 2 && (
            <Box
              component="label"
              sx={{
                display: 'flex',
                alignItems: 'center',
                gap: 0.5,
                borderBottom: 1,
                borderColor: 'divider',
                py: 0.25,
                cursor: 'pointer',
              }}
            >
              <Checkbox
                size="small"
                checked={selectableChosen === selectable.length}
                indeterminate={selectableChosen > 0 && selectableChosen < selectable.length}
                onChange={switchAll}
                slotProps={{
                  input: {
                    ...(selectableChosen > 0 && selectableChosen < selectable.length
                      ? { 'aria-checked': 'mixed' as const }
                      : {}),
                  },
                }}
              />
              <Typography sx={{ fontSize: 13.5, fontWeight: 500, color: 'text.primary' }}>
                {allLabel}
              </Typography>
            </Box>
          )}
          {entries.slice(0, shown).map((entry) => {
            const { checked, mixed } = rowState(entry.key)
            const covered = coveringParent !== undefined
            return (
              <Box
                key={entry.key}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 0.5,
                  minHeight: 40,
                  borderBottom: 1,
                  borderColor: 'divider',
                  '&:hover': { bgcolor: 'action.hover' },
                }}
              >
                {entry.selectable ? (
                  <Checkbox
                    size="small"
                    checked={checked || covered}
                    indeterminate={!checked && !covered && mixed}
                    disabled={covered || (Boolean(entry.blocked) && !checked)}
                    title={entry.blocked}
                    onChange={() => {
                      setDraft((prev) => toggle(prev, entry, level))
                      setApplyError(null)
                    }}
                    slotProps={{
                      input: {
                        'aria-label': entry.name,
                        ...(!checked && !covered && mixed
                          ? { 'aria-checked': 'mixed' as const }
                          : {}),
                      },
                    }}
                  />
                ) : (
                  <Box sx={{ width: 38, flexShrink: 0 }} />
                )}
                <Box sx={{ flex: 1, minWidth: 0, py: entry.blocked ? 0.5 : 0 }}>
                  <Typography
                    component={entry.openable && !entry.selectable ? 'button' : 'span'}
                    aria-label={
                      entry.openable && !entry.selectable ? `„${entry.name}“ öffnen` : undefined
                    }
                    onClick={
                      entry.openable && !entry.selectable
                        ? () => openLevel([...level, crumbOf(entry)])
                        : undefined
                    }
                    sx={{
                      ...ellipsis,
                      display: 'block',
                      width: '100%',
                      fontSize: 14,
                      textAlign: 'left',
                      border: 0,
                      bgcolor: 'transparent',
                      color: 'text.primary',
                      p: 0,
                      font: 'inherit',
                      cursor: entry.openable && !entry.selectable ? 'pointer' : 'default',
                    }}
                  >
                    <PathLabel path={entry.name} />
                  </Typography>
                </Box>
                {/* one tab stop per entry: where the name itself opens, the chevron is only a pointer target */}
                {entry.openable && (
                  <Tooltip disableInteractive title="Öffnen">
                    <IconButton
                      aria-label={`„${entry.name}“ öffnen`}
                      {...(!entry.selectable ? { tabIndex: -1, 'aria-hidden': true } : {})}
                      onClick={() => openLevel([...level, crumbOf(entry)])}
                    >
                      <ChevronRightIcon />
                    </IconButton>
                  </Tooltip>
                )}
              </Box>
            )
          })}
          {entries.length > shown && (
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 1, flexWrap: 'wrap' }}>
              <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                {shown} von {entries.length} angezeigt
              </Typography>
              <TextAction onClick={() => setShown((n) => n + PAGE)}>Weitere anzeigen</TextAction>
            </Box>
          )}
        </Box>
      )}
      {source.manual && (source.manual.kind === 'add' || level.length === 0) && (
        <Box sx={{ mt: 2 }}>
          {!manualOpen ? (
            <TextAction onClick={() => setManualOpen(true)}>Von Hand eintragen …</TextAction>
          ) : (
            <Box>
              <Typography
                component="label"
                htmlFor="selection-manual"
                sx={{ display: 'block', fontSize: 12, color: 'text.secondary', mb: '5px' }}
              >
                {source.manual.label}
              </Typography>
              <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start', flexWrap: 'wrap' }}>
                <TextField
                  id="selection-manual"
                  size="small"
                  value={manualText}
                  placeholder={source.manual.placeholder}
                  onChange={(e) => setManualText(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault()
                      void addManual()
                    }
                  }}
                  error={manualError !== null}
                  helperText={manualError}
                  sx={{ flex: '1 1 220px' }}
                  slotProps={{ htmlInput: { sx: { fontFamily: fontFamily.mono } } }}
                />
                <Button variant="outlined" onClick={() => void addManual()}>
                  {source.manual.kind === 'open' ? 'Öffnen' : 'Hinzufügen'}
                </Button>
              </Box>
            </Box>
          )}
        </Box>
      )}
    </Box>
  )

  return (
    <Dialog
      open
      onClose={() => void requestClose()}
      fullScreen={narrow}
      maxWidth="md"
      fullWidth
      aria-labelledby="selection-dialog-title"
      slotProps={{ paper: { sx: { maxHeight: narrow ? '100%' : 'min(760px, 88vh)' } } }}
    >
      <DialogTitle id="selection-dialog-title" sx={{ pb: 1 }}>
        {title}
      </DialogTitle>
      <Box sx={{ px: 3, pb: 1.5, borderBottom: 1, borderColor: 'divider' }}>
        {!reviewing && !source.nested && level.length === 0 ? null : reviewing ? (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Ausgewählt über alle Ebenen
          </Typography>
        ) : (
          <Box
            component="nav"
            aria-label="Ebenen"
            sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 0.5, mb: 1 }}
          >
            {[{ key: '', name: source.rootLabel }, ...level].map((crumb, i, all) => {
              const last = i === all.length - 1
              return (
                <Box
                  key={crumb.key || 'root'}
                  sx={{ display: 'flex', alignItems: 'center', minWidth: 0, maxWidth: '100%' }}
                >
                  {i > 0 && (
                    <Typography aria-hidden sx={{ color: 'text.secondary', px: 0.5 }}>
                      /
                    </Typography>
                  )}
                  <Tooltip disableInteractive title={crumb.name} enterDelay={600}>
                    {last ? (
                      <Typography
                        aria-current="location"
                        sx={{
                          ...ellipsis,
                          fontSize: 14,
                          fontWeight: 600,
                          maxWidth: { xs: 220, sm: 320 },
                        }}
                      >
                        {crumb.name}
                      </Typography>
                    ) : (
                      <Button
                        size="small"
                        onClick={() => openLevel(level.slice(0, i))}
                        sx={{ minWidth: 0, px: 0.5, maxWidth: { xs: 160, sm: 240 } }}
                      >
                        <Box component="span" sx={ellipsis}>
                          {crumb.name}
                        </Box>
                      </Button>
                    )}
                  </Tooltip>
                </Box>
              )
            })}
          </Box>
        )}
        {!reviewing && showSearch && (
          <TextField
            size="small"
            fullWidth
            value={search}
            onChange={(e) => {
              setSearch(e.target.value)
              setShown(PAGE)
            }}
            placeholder={source.searchLabel(level)}
            slotProps={{ htmlInput: { 'aria-label': source.searchLabel(level), type: 'search' } }}
          />
        )}
      </Box>
      <DialogContent ref={listRef} sx={{ pt: '8px !important' }}>
        {body}
      </DialogContent>
      <Box sx={{ px: 3, pt: 1 }} role="alert">
        {applyError && (
          <Typography sx={{ fontSize: 13, color: 'error.main' }}>{applyError}</Typography>
        )}
      </Box>
      <DialogActions
        sx={{
          px: 3,
          py: 1.5,
          borderTop: 1,
          borderColor: 'divider',
          flexWrap: 'wrap',
          gap: 1,
          justifyContent: 'space-between',
        }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, minWidth: 0 }}>
          <Typography sx={{ fontSize: 13.5, color: 'text.primary' }} aria-live="polite">
            {draft.length === 0 && source.emptyMeans
              ? source.emptyMeans
              : `${draft.length} ausgewählt`}
          </Typography>
          {(draft.length > 0 || reviewing) && (
            <TextAction onClick={() => setReviewing((r) => !r)}>
              {reviewing ? 'Zur Liste' : 'Auswahl ansehen'}
            </TextAction>
          )}
        </Box>
        <Box sx={{ display: 'flex', gap: 1, ml: 'auto' }}>
          <Button onClick={() => void requestClose()}>Abbrechen</Button>
          <Button variant="contained" onClick={apply}>
            Übernehmen
          </Button>
        </Box>
      </DialogActions>
    </Dialog>
  )
}
