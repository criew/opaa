import { useState } from 'react'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import FormField from './form/FormField'
import { formWidth } from '../theme/tokens'
import Typography from '@mui/material/Typography'
import { apiFieldErrors } from '../api/apiErrorDetails'
import { confirmAction } from '../stores/confirmStore'
import { notify } from '../stores/notificationStore'
import SettingValueRow from './settings/SettingValueRow'

export interface FactoryDefaultNumberSettingProps {
  /** The field's visible label and accessible name. */
  label: string
  /** The row's title, where the label is too long for it; the label otherwise. */
  title?: string
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
 * One whole number the system administration may set, as a value row with „Ändern“, with its
 * factory default named and restorable. A change and a reset are confirmed first; a failed save
 * is told at the field and announced as a message, and kept until the next edit.
 */
export default function FactoryDefaultNumberSetting({
  label,
  title = label,
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

  async function save(next: number | null): Promise<boolean> {
    setBusy(true)
    setSaveError(null)
    try {
      const inForce = await onSave(next)
      setDraft(String(inForce))
      notify(savedMessage(inForce), 'success')
      return true
    } catch (err: unknown) {
      const own = apiFieldErrors(err).find((error) => error.field.endsWith(field))
      const message =
        own?.message ?? (err instanceof Error ? err.message : 'Speichern fehlgeschlagen.')
      setSaveError(message)
      notify(`${failedMessage}: ${message}`, 'error')
      return false
    } finally {
      setBusy(false)
    }
  }

  /** Whether the row may close: saved or unchanged. */
  async function handleSave(): Promise<boolean> {
    if (busy || parsed === null) return false
    if (!changed) return true
    const { question, consequence, danger } = confirmChange(parsed)
    const confirmed = await confirmAction({
      question,
      consequence,
      confirmLabel: 'Ändern',
      tone: danger ? 'danger' : 'caution',
    })
    return confirmed ? save(parsed) : false
  }

  async function handleReset() {
    if (busy) return
    const { consequence, warning, danger } = confirmReset(value, defaultValue)
    const confirmed = await confirmAction({
      question: `Auf die Werkseinstellung von ${format(defaultValue)} zurücksetzen?`,
      consequence: warning ? `${warning} ${consequence}` : consequence,
      confirmLabel: 'Zurücksetzen',
      tone: warning || danger ? 'danger' : 'caution',
    })
    if (confirmed) await save(null)
  }

  return (
    <SettingValueRow
      title={title}
      headingLevel="h3"
      value={format(value)}
      onOpen={() => {
        setDraft(String(value))
        setSaveError(null)
      }}
      onSave={handleSave}
      editor={
        <FormField
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
          numeric
          sx={{ maxWidth: formWidth.column }}
        />
      }
    >
      <Stack spacing={1} sx={{ alignItems: 'flex-start' }}>
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
    </SettingValueRow>
  )
}

/** A typed whole number within `min` and `max`; anything else is refused before sending. */
function parseWithin(draft: string, min: number, max: number): number | null {
  const text = draft.trim()
  if (!/^\d+$/.test(text)) return null
  const number = Number(text)
  return number >= min && number <= max ? number : null
}
