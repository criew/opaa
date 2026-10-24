import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import SelectionDialog from '../sourceKit/SelectionDialog'
import { plainMessage } from '../sourceKit/plainMessage'
import {
  toggle,
  type BrowseResult,
  type Crumb,
  type SelectionItem,
  type SelectionSource,
} from '../sourceKit/selection'

/** Up to this many flat entries stand as checkboxes in the row; more go through the dialog. */
const INLINE_MAX = 7
const LOADING_DELAY = 300
const SHOWN_NAMES = 5

interface ScopePickerProps {
  /** Id of the group; the error summary jumps to its first control. */
  id: string
  source: SelectionSource
  value: SelectionItem[]
  onChange: (items: SelectionItem[]) => void
  /** The radio pair „Alle … / Nur ausgewählte …“, where the source can read everything. */
  all?: { active: boolean; onChange: (all: boolean) => void }
  error?: string | null
  /** Changes whenever the listing would answer differently (address, credentials). */
  loadKey: string
  /** Whether the outermost level holds choosable entries; SharePoint sites only lead deeper. */
  inline?: boolean
  /** Opens the dialog below the first level, e.g. „Unterordner auswählen …“. */
  deeperLabel?: string
  initialLevel?: Crumb[]
  /** How a chosen entry is named in the row; its own name by default. */
  labelOf?: (item: SelectionItem) => string
}

type Listing = { key: string; result?: BrowseResult; error?: string }

/**
 * The element „Umfang“ of every connector: the radio pair where the source can be read whole,
 * then up to seven flat entries as checkboxes in place, otherwise the chosen names with „Ändern“,
 * which opens the shared selection dialog (search, levels, „Alle n auswählen“).
 */
export default function ScopePicker({
  id,
  source,
  value,
  onChange,
  all,
  error,
  loadKey,
  inline = true,
  deeperLabel,
  initialLevel,
  labelOf = (item) => item.name,
}: ScopePickerProps) {
  const plural = source.noun[1]
  const [dialogOpen, setDialogOpen] = useState(false)
  const [listing, setListing] = useState<Listing | null>(null)
  const [showLoading, setShowLoading] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const wantsList = inline && !all?.active
  const token = `${loadKey}#${attempt}`

  useEffect(() => {
    if (!wantsList) return
    let alive = true
    const delay = setTimeout(() => alive && setShowLoading(true), LOADING_DELAY)
    source
      .list([], '')
      .then((result) => alive && setListing({ key: token, result }))
      .catch(
        (err: unknown) =>
          alive &&
          setListing({
            key: token,
            error: err instanceof Error ? err.message : `${plural} ließen sich nicht laden.`,
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
    // the token names everything the listing depends on
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wantsList, token])

  const current = listing?.key === token ? listing : null
  const entries = current?.result?.entries ?? []
  const inlineFits =
    current?.result !== undefined &&
    entries.length > 0 &&
    entries.length <= INLINE_MAX &&
    value.every((item) => item.trail.length === 0 && entries.some((e) => e.key === item.key))
  const chosen = new Set(value.map((item) => item.key))
  const errorId = `${id}-error`

  const radios = all && (
    <RadioGroup
      aria-label={`Umfang: ${plural}`}
      value={all.active ? 'all' : 'some'}
      onChange={(e) => all.onChange(e.target.value === 'all')}
    >
      <FormControlLabel value="all" control={<Radio size="small" />} label={`Alle ${plural}`} />
      <FormControlLabel
        value="some"
        control={<Radio size="small" />}
        label={`Nur ausgewählte ${plural}`}
      />
    </RadioGroup>
  )

  const deniedWords = /403|verweiger|Berechtigung|AccessDenied|gesperrt|ACCESS_DENIED/i
  const stateLine = (() => {
    if (!wantsList) return null
    if (current === null)
      return showLoading ? (
        <Typography role="status" sx={{ fontSize: 13.5, color: 'text.secondary' }}>
          {plural} werden geladen …
        </Typography>
      ) : null
    if (current.error)
      return (
        <Box>
          <Typography sx={{ fontSize: 13.5, fontWeight: 500, color: 'text.primary' }}>
            {deniedWords.test(current.error)
              ? `Kein Zugriff auf die ${plural}.`
              : `${plural} ließen sich nicht laden.`}
          </Typography>
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
            {plainMessage(current.error)}
          </Typography>
          <Button size="small" sx={{ ml: -0.5 }} onClick={() => setAttempt((n) => n + 1)}>
            Erneut laden
          </Button>
        </Box>
      )
    if (entries.length === 0 && current.result?.complete)
      return (
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
          Keine {plural} vorhanden.
        </Typography>
      )
    if (current.result && !current.result.complete && current.result.message)
      return (
        <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
          {plainMessage(current.result.message)}
        </Typography>
      )
    return null
  })()

  const picker = all?.active ? null : inlineFits ? (
    <Box>
      <Box
        role="group"
        aria-label={all ? `Ausgewählte ${plural}` : undefined}
        aria-labelledby={all ? undefined : `${id}-label`}
        aria-describedby={error ? errorId : undefined}
      >
        {entries.map((entry) => (
          <FormControlLabel
            key={entry.key}
            sx={{ display: 'flex', mr: 0, minWidth: 0 }}
            control={
              <Checkbox
                size="small"
                checked={chosen.has(entry.key)}
                disabled={Boolean(entry.blocked) && !chosen.has(entry.key)}
                onChange={() => onChange(toggle(value, entry, []))}
              />
            }
            label={
              <Typography
                title={entry.name}
                sx={{
                  fontSize: 14,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {entry.name}
              </Typography>
            }
          />
        ))}
      </Box>
      {source.nested && deeperLabel && (
        <Button size="small" sx={{ ml: -0.5, mt: 0.5 }} onClick={() => setDialogOpen(true)}>
          {deeperLabel}
        </Button>
      )}
    </Box>
  ) : (
    <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.5 }}>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        {value.length === 0 ? (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary', pt: 0.5 }}>
            Keine {plural} ausgewählt
          </Typography>
        ) : (
          <Box
            component="ul"
            aria-label={`Ausgewählte ${plural}`}
            sx={{ m: 0, p: 0, listStyle: 'none' }}
          >
            {value.slice(0, SHOWN_NAMES).map((item) => (
              <Typography
                component="li"
                key={item.key}
                title={labelOf(item)}
                sx={{
                  fontSize: 13.5,
                  lineHeight: '24px',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {labelOf(item)}
              </Typography>
            ))}
            {value.length > SHOWN_NAMES && (
              <Typography
                component="li"
                sx={{ fontSize: 13.5, lineHeight: '24px', color: 'text.secondary' }}
              >
                und {value.length - SHOWN_NAMES} weitere
              </Typography>
            )}
          </Box>
        )}
      </Box>
      <Button
        variant="outlined"
        size="small"
        sx={{ flexShrink: 0 }}
        onClick={() => setDialogOpen(true)}
        aria-describedby={error ? errorId : undefined}
      >
        {value.length ? 'Ändern' : 'Auswählen …'}
      </Button>
    </Box>
  )

  return (
    <Box id={id}>
      {!all && <FieldLabel id={`${id}-label`}>{plural}</FieldLabel>}
      {radios}
      {!all?.active && (
        <Box
          sx={{
            mt: all ? 1 : 0,
            pl: all ? 3.5 : 0,
            display: 'grid',
            gridTemplateColumns: 'minmax(0, 1fr)',
            gap: 1,
          }}
        >
          {stateLine}
          {picker}
        </Box>
      )}
      {error && (
        <Typography id={errorId} sx={{ fontSize: 12, color: 'error.main', mt: 0.5 }}>
          {error}
        </Typography>
      )}
      <SelectionDialog
        open={dialogOpen}
        title={`${plural} auswählen`}
        source={source}
        value={value}
        onApply={onChange}
        onClose={() => setDialogOpen(false)}
        initialLevel={initialLevel}
      />
    </Box>
  )
}
