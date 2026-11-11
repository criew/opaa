import { useCallback, useEffect, useState } from 'react'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import InputAdornment from '@mui/material/InputAdornment'
import type { FullSyncRhythmResponse } from '@/shared/api/types'
import { listFullSyncRhythms, updateFullSyncRhythm } from '../api/fullSyncRhythmApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import SettingValueRow from '@/shared/ui/settings/SettingValueRow'
import PageSection from '@/shared/ui/PageSection'
import { contentWidth, formWidth } from '@/shared/theme/tokens'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

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

interface RhythmRowProps {
  rhythm: FullSyncRhythmResponse
  onSaved: (saved: FullSyncRhythmResponse) => void
}

/** One source type; a failed save is told at its own field and kept until the next edit. */
function RhythmRow({ rhythm, onSaved }: RhythmRowProps) {
  const [draft, setDraft] = useState(String(rhythm.intervalDays))
  const [busy, setBusy] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const name = rhythm.displayName
  const days = parseDays(draft)
  const changed = days !== null && days !== rhythm.intervalDays

  async function save(next: number | null): Promise<boolean> {
    setBusy(true)
    setSaveError(null)
    try {
      const saved = await updateFullSyncRhythm(rhythm.sourceType, next)
      onSaved(saved)
      notify(
        `Der Vollabgleich-Rhythmus von ${name} ist jetzt ${daysLabel(saved.intervalDays)}.`,
        'success',
      )
      return true
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : 'Der Rhythmus konnte nicht gespeichert werden.'
      setSaveError(message)
      notify(`Rhythmus von ${name} nicht gespeichert: ${message}`, 'error')
      return false
    } finally {
      setBusy(false)
    }
  }

  /** Whether the row may close: saved or unchanged. */
  async function handleSave(): Promise<boolean> {
    if (busy || days === null) return false
    if (!changed) return true
    const confirmed = await confirmAction({
      question: `Vollabgleich-Rhythmus von ${name} auf ${daysLabel(days)} ändern?`,
      consequence: `Gilt ab dem nächsten Lauf für ${scopeOf(rhythm)}. Ein kürzerer Rhythmus entfernt früher, was in der Quelle gelöscht wurde, und stellt mehr Anfragen an die Quelle. Die Änderung steht im Revisionsprotokoll.`,
      confirmLabel: 'Ändern',
      tone: 'caution',
    })
    if (!confirmed) return false
    return save(days)
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

  return (
    <SettingValueRow
      title={name}
      headingLevel="h3"
      value={
        rhythm.overridden
          ? `${daysLabel(rhythm.intervalDays)} · eigener Wert (Werkseinstellung: ${daysLabel(rhythm.defaultIntervalDays)})`
          : `${daysLabel(rhythm.intervalDays)} · Werkseinstellung`
      }
      onOpen={() => {
        setDraft(String(rhythm.intervalDays))
        setSaveError(null)
      }}
      onSave={handleSave}
      editor={
        <FormField
          label={`Vollabgleich von ${name} alle … Tage`}
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
      }
    >
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
    </SettingValueRow>
  )
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
        <Stack
          role="group"
          aria-label="Vollabgleich-Rhythmus je Quellart"
          sx={{ maxWidth: contentWidth.form }}
        >
          {rhythms.map((rhythm) => (
            <RhythmRow
              key={`${rhythm.sourceType}-${rhythm.intervalDays}-${rhythm.overridden}`}
              rhythm={rhythm}
              onSaved={replace}
            />
          ))}
        </Stack>
      )}
    </PageSection>
  )
}
