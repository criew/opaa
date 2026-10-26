import { useState, type FormEvent } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { apiFieldErrors } from '../api/apiErrorDetails'
import { confirmAction } from '../stores/confirmStore'
import { notify } from '../stores/notificationStore'
import BusyButton from './a11y/BusyButton'

export interface FactoryDefaultNumberSettingProps {
  /** The field's visible label and accessible name. */
  label: string
  /** The API field of the value, to find its own message among the field errors. */
  field: string
  value: number
  defaultValue: number
  overridden: boolean
  min: number
  max: number
  /** The value in words, e.g. „12 Monate“. */
  format: (value: number) => string
  /**
   * The question and consequence that confirm a new value; `danger` asks with the strongest tone,
   * where the new value loosens a safeguard.
   */
  confirmChange: (next: number) => { question: string; consequence: string; danger?: boolean }
  /**
   * What returning to the factory default changes. A non-null `warning` asks with the strongest
   * tone: the factory default is stricter or shorter than the value in force.
   */
  confirmReset: (
    current: number,
    factory: number,
  ) => { consequence: string; warning: string | null; danger?: boolean }
  /** The announcement after a save, with the value now in force. */
  savedMessage: (value: number) => string
  /** The beginning of the announcement of a failed save, e.g. „Schwelle nicht gespeichert“. */
  failedMessage: string
  /** Saves `next`; `null` returns to the factory default. Resolves with the value in force. */
  onSave: (next: number | null) => Promise<number>
}

/**
 * One whole number the system administration may set, with its factory default named and
 * restorable. A change and a reset are confirmed first; a failed save is told at the field and
 * announced as a message, and kept until the next edit.
 */
export default function FactoryDefaultNumberSetting({
  label,
  field,
  value,
  defaultValue,
  overridden,
  min,
  max,
  format,
  confirmChange,
  confirmReset,
  savedMessage,
  failedMessage,
  onSave,
}: FactoryDefaultNumberSettingProps) {
  const [draft, setDraft] = useState(String(value))
  const [busy, setBusy] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const parsed = parseWithin(draft, min, max)
  const changed = parsed !== null && (parsed !== value || !overridden)
  const rangeError = `Bitte eine ganze Zahl von ${min} bis ${max} eingeben.`

  async function save(next: number | null) {
    setBusy(true)
    setSaveError(null)
    try {
      const inForce = await onSave(next)
      setDraft(String(inForce))
      notify(savedMessage(inForce), 'success')
    } catch (err: unknown) {
      const own = apiFieldErrors(err).find((error) => error.field.endsWith(field))
      const message =
        own?.message ?? (err instanceof Error ? err.message : 'Speichern fehlgeschlagen.')
      setSaveError(message)
      notify(`${failedMessage}: ${message}`, 'error')
    } finally {
      setBusy(false)
    }
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (busy || !changed || parsed === null) return
    const { question, consequence, danger } = confirmChange(parsed)
    const confirmed = await confirmAction({
      question,
      consequence,
      confirmLabel: 'Ändern',
      tone: danger ? 'danger' : 'caution',
    })
    if (confirmed) await save(parsed)
  }

  async function handleReset() {
    if (busy) return
    const { consequence, warning, danger } = confirmReset(value, defaultValue)
    const confirmed = await confirmAction({
      question: `Auf die Werkseinstellung von ${format(defaultValue)} zurücksetzen?`,
      consequence: warning ? `${warning} ${consequence}` : consequence,
      confirmLabel: 'Werkseinstellung wiederherstellen',
      tone: warning || danger ? 'danger' : 'caution',
    })
    if (confirmed) await save(null)
  }

  return (
    <Stack spacing={1} sx={{ alignItems: 'flex-start' }}>
      <Box
        component="form"
        onSubmit={(event) => void handleSubmit(event)}
        noValidate
        sx={{ display: 'flex', gap: 1.5, alignItems: 'flex-start', flexWrap: 'wrap' }}
      >
        <TextField
          label={label}
          size="small"
          value={draft}
          onChange={(event) => {
            setDraft(event.target.value)
            setSaveError(null)
          }}
          error={parsed === null || saveError !== null}
          helperText={
            parsed === null ? rangeError : (saveError ?? `Von ${format(min)} bis ${format(max)}.`)
          }
          slotProps={{ htmlInput: { inputMode: 'numeric' } }}
          sx={{ width: 280 }}
        />
        <BusyButton
          type="submit"
          variant="outlined"
          size="small"
          busy={busy}
          busyAnnouncement="Wird gespeichert"
          disabled={!changed && !busy}
          sx={{ mt: 0.25 }}
        >
          Speichern
        </BusyButton>
      </Box>
      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
        {overridden
          ? `Eigener Wert: ${format(value)} (Werkseinstellung: ${format(defaultValue)})`
          : `Werkseinstellung: ${format(defaultValue)}`}
      </Typography>
      {overridden && (
        <Button variant="text" size="small" disabled={busy} onClick={() => void handleReset()}>
          Werkseinstellung wiederherstellen
        </Button>
      )}
    </Stack>
  )
}

/** A typed whole number within `min` and `max`; anything else is refused before sending. */
function parseWithin(draft: string, min: number, max: number): number | null {
  const text = draft.trim()
  if (!/^\d+$/.test(text)) return null
  const number = Number(text)
  return number >= min && number <= max ? number : null
}
