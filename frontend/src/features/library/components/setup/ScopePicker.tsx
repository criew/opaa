import { useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import Link from '@mui/material/Link'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Typography from '@mui/material/Typography'
import SelectionDialog from '../sourceKit/SelectionDialog'
import { plainMessage } from '../sourceKit/plainMessage'
import {
  toggle,
  type BrowseResult,
  type Crumb,
  type SelectionItem,
  type SelectionSource,
} from '../sourceKit/selection'
import { counted } from './connectors/common'
import PathLabel from './PathLabel'
import { SummaryValue } from './SummaryRow'
import { TEXT_LINK } from './rowStyles'

/** Up to this many flat entries stand as checkboxes in the row; more go through the dialog. */
const INLINE_MAX = 7
const LOADING_DELAY = 300

interface ScopePickerProps {
  /** Id of the group; the error summary jumps to its first control. */
  id: string
  source: SelectionSource
  value: SelectionItem[]
  onChange: (items: SelectionItem[]) => void
  /** The radio pair „Alle … / Nur ausgewählte …“, where the source can be read whole. */
  all?: { active: boolean; onChange: (all: boolean) => void; label?: string }
  error?: string | null
  /** Changes whenever the listing would answer differently (address, credentials). */
  loadKey: string
  /** Whether the outermost level holds choosable entries; SharePoint sites only lead deeper. */
  inline?: boolean
  initialLevel?: Crumb[]
  /** How a chosen entry is named; its own name by default. */
  labelOf?: (item: SelectionItem) => string
  /** Opens the dialog at once: the row's „Ändern“ leads straight into it. */
  autoOpen?: boolean
  /** The dialog closed; `applied` when „Übernehmen“ took the selection. */
  onDone?: (applied: boolean) => void
}

type Listing = { key: string; result?: BrowseResult; error?: string }

function TextLink({ onClick, children }: { onClick: () => void; children: string }) {
  return (
    <Link component="button" type="button" underline="hover" onClick={onClick} sx={TEXT_LINK}>
      {children}
    </Link>
  )
}

/**
 * The element „Umfang“ of every connector: the radio pair where the source can be read whole, up to
 * seven flat entries as checkboxes in place, otherwise the chosen entries as a list with the link
 * „<Objekte> auswählen …“ into the shared selection dialog.
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
  initialLevel,
  labelOf = (item) => item.name,
  autoOpen = false,
  onDone,
}: ScopePickerProps) {
  const [one, plural] = source.noun
  const [dialogOpen, setDialogOpen] = useState(autoOpen)
  const [manualFirst, setManualFirst] = useState(false)
  const applied = useRef(false)
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
  const openDialog = (manual = false) => {
    applied.current = false
    setManualFirst(manual)
    setDialogOpen(true)
  }
  const selectLink = <TextLink onClick={() => openDialog()}>{`${plural} auswählen …`}</TextLink>
  const manualLink = source.manual && (
    <TextLink onClick={() => openDialog(true)}>Von Hand eintragen …</TextLink>
  )

  const deniedWords = /403|verweiger|Berechtigung|AccessDenied|gesperrt|ACCESS_DENIED/i
  let body: React.ReactNode
  if (!wantsList || current) {
    if (current?.error) {
      const detail = plainMessage(current.error)
      body = (
        <Box>
          <Typography sx={{ fontSize: 13.5, color: 'text.primary' }}>
            {deniedWords.test(current.error)
              ? `Kein Zugriff auf die ${plural}.`
              : `${plural} ließen sich nicht laden.`}
          </Typography>
          {detail && (
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>{detail}</Typography>
          )}
          <Box sx={{ display: 'flex', gap: 2, mt: 0.5 }}>
            <TextLink onClick={() => setAttempt((n) => n + 1)}>Erneut laden</TextLink>
            {manualLink}
          </Box>
        </Box>
      )
    } else if (wantsList && entries.length === 0 && current?.result?.complete) {
      body = (
        <Box>
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Keine {plural} vorhanden.
          </Typography>
          {manualLink && <Box sx={{ mt: 0.5 }}>{manualLink}</Box>}
        </Box>
      )
    } else if (inlineFits) {
      body = (
        <Box>
          {current?.result && !current.result.complete && (
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
              Liste unvollständig.
            </Typography>
          )}
          <Box role="group" aria-label={plural} aria-describedby={error ? errorId : undefined}>
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
                  <Box sx={{ fontSize: 14, minWidth: 0 }}>
                    <PathLabel path={entry.name} />
                  </Box>
                }
              />
            ))}
          </Box>
          {source.nested && <Box sx={{ mt: 0.5 }}>{selectLink}</Box>}
        </Box>
      )
    } else {
      body = (
        <Box>
          {current?.result && !current.result.complete && (
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
              Liste unvollständig.
            </Typography>
          )}
          {value.length > 0 && (
            <SummaryValue
              summary={{
                lead: counted(value.length, [one, plural]),
                items: value.map(labelOf),
              }}
            />
          )}
          <Box
            sx={{ mt: value.length > 0 ? 0.5 : 0 }}
            aria-describedby={error ? errorId : undefined}
          >
            {selectLink}
          </Box>
        </Box>
      )
    }
  } else {
    body = showLoading ? (
      <Typography role="status" sx={{ fontSize: 13.5, color: 'text.secondary' }}>
        {plural} werden geladen …
      </Typography>
    ) : null
  }

  return (
    <Box id={id}>
      {all && (
        <RadioGroup
          aria-label={`Umfang: ${plural}`}
          value={all.active ? 'all' : 'some'}
          onChange={(e) => all.onChange(e.target.value === 'all')}
        >
          <FormControlLabel
            value="all"
            control={<Radio size="small" />}
            label={all.label ?? `Alle ${plural}`}
          />
          <FormControlLabel
            value="some"
            control={<Radio size="small" />}
            label={`Nur ausgewählte ${plural}`}
          />
        </RadioGroup>
      )}
      {!all?.active && <Box sx={{ pl: all ? 3.5 : 0, mt: all ? 0.5 : 0 }}>{body}</Box>}
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
        onApply={(items) => {
          applied.current = true
          onChange(items)
        }}
        onClose={() => {
          setDialogOpen(false)
          onDone?.(applied.current)
        }}
        initialLevel={initialLevel}
        startManual={manualFirst}
      />
    </Box>
  )
}
