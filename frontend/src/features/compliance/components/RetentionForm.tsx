import { useState } from 'react'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import SettingsForm from '@/shared/ui/settings/SettingsForm'
import {
  periodLabel,
  type RetentionField,
  type RetentionToggle,
  type RetentionValues,
} from '../utils/retentionPeriod'
import { fontSize } from '@/shared/theme/tokens'

interface RetentionFormProps {
  fields: RetentionField[]
  toggle?: RetentionToggle
  values: RetentionValues
  defaults: RetentionValues
  overridden: boolean
  /** Saves the values; resolves to whether they were saved. */
  onSave: (values: RetentionValues) => Promise<boolean>
  /** Returns to the factory defaults; resolves to whether that happened. */
  onReset: () => Promise<boolean>
}

function parse(draft: string, field: RetentionField): number | null {
  const text = draft.trim()
  if (!/^\d+$/.test(text)) return null
  const value = Number(text)
  return value >= field.min && value <= field.max ? value : null
}

function drafts(fields: RetentionField[], values: RetentionValues): Record<string, string> {
  return Object.fromEntries(fields.map((field) => [field.key, String(values[field.key])]))
}

/**
 * The form of one retention setting: whole numbers within their bounds, an optional checkbox that
 * takes effect with „Speichern“, the state „Werkseinstellung“ or „Eigener Wert“ and the way back
 * to the factory defaults. Confirming and saving is the caller's; the form only sends values that
 * passed their bounds.
 */
export default function RetentionForm({
  fields,
  toggle,
  values,
  defaults,
  overridden,
  onSave,
  onReset,
}: RetentionFormProps) {
  const [draft, setDraft] = useState(() => drafts(fields, values))
  const [switched, setSwitched] = useState(toggle ? Boolean(values[toggle.key]) : false)
  const [busy, setBusy] = useState(false)

  const parsed = Object.fromEntries(
    fields.map((field) => [field.key, parse(draft[field.key], field)]),
  )
  const valid = fields.every((field) => parsed[field.key] !== null)
  const changed =
    fields.some((field) => parsed[field.key] !== values[field.key]) ||
    (toggle !== undefined && switched !== values[toggle.key])

  const defaultText = [
    ...(toggle ? [`${toggle.label}: ${defaults[toggle.key] ? 'ja' : 'nein'}`] : []),
    ...fields.map(
      (field) => `${field.label}: ${periodLabel(Number(defaults[field.key]), field.unit)}`,
    ),
  ].join(', ')

  async function run(action: () => Promise<boolean>) {
    setBusy(true)
    try {
      await action()
    } finally {
      setBusy(false)
    }
  }

  function discard() {
    setDraft(drafts(fields, values))
    setSwitched(toggle ? Boolean(values[toggle.key]) : false)
  }

  async function handleSave() {
    if (busy || !valid) return
    const next: RetentionValues = toggle ? { [toggle.key]: switched } : {}
    fields.forEach((field) => {
      next[field.key] = parsed[field.key] as number
    })
    await run(() => onSave(next))
  }

  return (
    <Stack spacing={2} sx={{ alignItems: 'flex-start' }}>
      <SettingsForm
        onSave={handleSave}
        busy={busy}
        busyAnnouncement="Frist wird gespeichert"
        dirty={changed}
        onDiscard={discard}
        sx={{ width: '100%' }}
      >
        <Stack spacing={2}>
          {toggle && (
            <FormControlLabel
              control={
                <Checkbox
                  checked={switched}
                  onChange={(event) => setSwitched(event.target.checked)}
                />
              }
              label={toggle.label}
            />
          )}
          {fields.map((field) => (
            <FormField
              key={field.key}
              label={field.label}
              size="small"
              value={draft[field.key]}
              onChange={(event) => setDraft({ ...draft, [field.key]: event.target.value })}
              error={parsed[field.key] === null}
              helperText={`Erlaubt sind ${periodLabel(field.min, field.unit)} bis ${periodLabel(field.max, field.unit)}.`}
              numeric
              slotProps={{ htmlInput: { inputMode: 'numeric' } }}
            />
          ))}
        </Stack>
      </SettingsForm>
      <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
        {overridden
          ? `Es gilt ein eigener Wert. Werkseinstellung: ${defaultText}.`
          : 'Es gilt die Werkseinstellung.'}
      </Typography>
      {overridden && (
        <Button variant="text" size="small" disabled={busy} onClick={() => void run(onReset)}>
          Werkseinstellung wiederherstellen
        </Button>
      )}
    </Stack>
  )
}
