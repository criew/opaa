import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type FormEvent,
  type ReactNode,
} from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import InputAdornment from '@mui/material/InputAdornment'
import Typography from '@mui/material/Typography'
import type { FullSyncRhythmResponse } from '@/shared/api/types'
import { listFullSyncRhythms, updateFullSyncRhythm } from '../api/fullSyncRhythmApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import PageSection from '@/shared/ui/PageSection'
import AdminList from '@/shared/ui/list/AdminList'
import { formWidth } from '@/shared/theme/tokens'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'
import { contentWidth } from '@/shared/theme/tokens'

const MIN_DAYS = 1
const MAX_DAYS = 365
const RANGE_ERROR = `Bitte eine ganze Zahl von ${MIN_DAYS} bis ${MAX_DAYS} eingeben.`

/** Source types whose libraries have no rhythm of their own: the value holds for all of them. */
const WITHOUT_OWN_RHYTHM = new Set(['NEXTCLOUD'])

/** Which libraries a change reaches, in words. */
function scopeOf(rhythm: FullSyncRhythmResponse): string {
  return WITHOUT_OWN_RHYTHM.has(rhythm.sourceType)
    ? `alle ${rhythm.displayName}-Bibliotheken`
    : `alle ${rhythm.displayName}-Bibliotheken ohne eigenen Rhythmus`
}

/** Days in words: „1 Tag“, „7 Tage“. */
function daysLabel(days: number): string {
  return days === 1 ? '1 Tag' : `${days} Tage`
}

/** Typed days, whole numbers from 1 to 365 only; anything else is refused before sending. */
function parseDays(draft: string): number | null {
  const text = draft.trim()
  if (!/^\d+$/.test(text)) return null
  const days = Number(text)
  return days >= MIN_DAYS && days <= MAX_DAYS ? days : null
}

interface RhythmRowData {
  rhythm: FullSyncRhythmResponse
  onSaved: (saved: FullSyncRhythmResponse) => void
}

type RowPart = 'name' | 'value' | 'origin'

/** The three cells of one row; they share the row's draft and its running save. */
const RowParts = createContext<Record<RowPart, ReactNode> | null>(null)

function Part({ part }: { part: RowPart }) {
  return useContext(RowParts)?.[part] ?? null
}

/** One source type; a failed save is told at its own field and kept until the next edit. */
function RhythmRow({ row, children }: { row: RhythmRowData; children: ReactNode }) {
  const { rhythm, onSaved } = row
  const [draft, setDraft] = useState(String(rhythm.intervalDays))
  const [busy, setBusy] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const name = rhythm.displayName
  const days = parseDays(draft)
  const changed = days !== null && days !== rhythm.intervalDays

  async function save(next: number | null) {
    setBusy(true)
    setSaveError(null)
    try {
      const saved = await updateFullSyncRhythm(rhythm.sourceType, next)
      onSaved(saved)
      notify(
        `Der Vollabgleich-Rhythmus von ${name} ist jetzt ${daysLabel(saved.intervalDays)}.`,
        'success',
      )
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : 'Der Rhythmus konnte nicht gespeichert werden.'
      setSaveError(message)
      notify(`Rhythmus von ${name} nicht gespeichert: ${message}`, 'error')
    } finally {
      setBusy(false)
    }
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (busy || !changed || days === null) return
    const confirmed = await confirmAction({
      question: `Vollabgleich-Rhythmus von ${name} auf ${daysLabel(days)} ändern?`,
      consequence: `Gilt ab dem nächsten Lauf für ${scopeOf(rhythm)}. Ein kürzerer Rhythmus entfernt früher, was in der Quelle gelöscht wurde, und stellt mehr Anfragen an die Quelle. Die Änderung steht im Revisionsprotokoll.`,
      confirmLabel: 'Ändern',
      tone: 'caution',
    })
    if (!confirmed) return
    await save(days)
  }

  async function handleReset() {
    if (busy) return
    const confirmed = await confirmAction({
      question: `Vollabgleich-Rhythmus von ${name} auf die Werkseinstellung von ${daysLabel(rhythm.defaultIntervalDays)} zurücksetzen?`,
      consequence: `Gilt ab dem nächsten Lauf für ${scopeOf(rhythm)}. Die Änderung steht im Revisionsprotokoll.`,
      confirmLabel: 'Zurücksetzen',
      tone: 'caution',
    })
    if (!confirmed) return
    await save(null)
  }

  const parts: Record<RowPart, ReactNode> = {
    name,
    value: (
      <Box
        component="form"
        onSubmit={(event) => void handleSubmit(event)}
        noValidate
        sx={{ display: 'flex', gap: 1.5, alignItems: 'flex-start', flexWrap: 'wrap' }}
      >
        <FormField
          label={`Vollabgleich von ${name} alle … Tage`}
          hideLabel
          size="small"
          value={draft}
          onChange={(event) => {
            setDraft(event.target.value)
            setSaveError(null)
          }}
          error={days === null || saveError !== null}
          helperText={days === null ? RANGE_ERROR : (saveError ?? undefined)}
          slotProps={{
            htmlInput: { inputMode: 'numeric' },
            input: { endAdornment: <InputAdornment position="end">Tage</InputAdornment> },
          }}
          sx={{ width: formWidth.number }}
        />
        <BusyButton
          type="submit"
          variant="outlined"
          size="small"
          busy={busy}
          busyAnnouncement="Rhythmus wird gespeichert"
          disabled={!changed && !busy}
          aria-label={`Rhythmus von ${name} speichern`}
          sx={{ mt: 0.25 }}
        >
          Speichern
        </BusyButton>
      </Box>
    ),
    origin: (
      <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
        <Typography variant="body2">
          {rhythm.overridden
            ? `Eigener Wert (Werkseinstellung: ${daysLabel(rhythm.defaultIntervalDays)})`
            : 'Werkseinstellung'}
        </Typography>
        {rhythm.overridden && (
          <Button
            variant="text"
            size="small"
            disabled={busy}
            onClick={() => void handleReset()}
            aria-label={`Rhythmus von ${name} auf die Werkseinstellung zurücksetzen`}
          >
            Werkseinstellung wiederherstellen
          </Button>
        )}
      </Stack>
    ),
  }
  return <RowParts.Provider value={parts}>{children}</RowParts.Provider>
}

/**
 * The installation-wide rhythm of the full sync per source type. A library without a rhythm of its
 * own follows it; the server answers the value in force and the factory default, none is derived.
 */
export default function FullSyncRhythmSection() {
  const [rhythms, setRhythms] = useState<FullSyncRhythmResponse[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const load = useCallback(
    () =>
      listFullSyncRhythms()
        .then((loaded) => {
          setRhythms(loaded)
          setLoadError(null)
        })
        .catch((err: unknown) => {
          setLoadError(
            err instanceof Error
              ? err.message
              : 'Der Vollabgleich-Rhythmus konnte nicht geladen werden.',
          )
        }),
    [],
  )

  useEffect(() => {
    void load()
  }, [load])

  function replace(saved: FullSyncRhythmResponse) {
    setRhythms((current) =>
      current === null
        ? current
        : current.map((rhythm) => (rhythm.sourceType === saved.sourceType ? saved : rhythm)),
    )
  }

  return (
    <PageSection
      title="Vollabgleich-Rhythmus"
      description="Nach wie vielen Tagen eine Bibliothek ohne eigenen Rhythmus ihre Quelle wieder vollständig abgleicht; erst dann entfällt, was die Quelle nicht einzeln als gelöscht meldet."
    >
      {loadError ? (
        <LoadError
          message="Der Vollabgleich-Rhythmus konnte nicht geladen werden."
          detail={loadError}
          onRetry={() => void load()}
        />
      ) : rhythms === null ? (
        <LoadingState shape="lines" count={2} />
      ) : (
        <Box sx={{ maxWidth: contentWidth.form }}>
          <AdminList<RhythmRowData>
            label="Vollabgleich-Rhythmus je Quellart"
            rows={rhythms.map((rhythm) => ({ rhythm, onSaved: replace }))}
            rowKey={({ rhythm }) =>
              `${rhythm.sourceType}-${rhythm.intervalDays}-${rhythm.overridden}`
            }
            rowLabel={({ rhythm }) => rhythm.displayName}
            rowScope={RhythmRow}
            columns={[
              {
                id: 'name',
                head: 'Quellart',
                width: '24%',
                wrap: true,
                cell: () => <Part part="name" />,
              },
              { id: 'value', head: 'Vollabgleich', wrap: true, cell: () => <Part part="value" /> },
              {
                id: 'origin',
                head: 'Gilt',
                width: '34%',
                wrap: true,
                cell: () => <Part part="origin" />,
              },
            ]}
          />
        </Box>
      )}
    </PageSection>
  )
}
