import { useState, type FormEvent } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import {
  periodLabel,
  type RetentionField,
  type RetentionToggle,
  type RetentionValues,
} from '../utils/retentionPeriod'

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
 * The form of one retention setting: whole numbers within their bounds, an optional switch, the
 * state „Werkseinstellung“ or „Eigener Wert“ and the way back to the factory defaults. Confirming
 * and saving is the caller's; the form only sends values that passed their bounds.
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

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (busy || !valid || !changed) return
    const next: RetentionValues = toggle ? { [toggle.key]: switched } : {}
    fields.forEach((field) => {
      next[field.key] = parsed[field.key] as number
    })
    await run(() => onSave(next))
  }

  return (
    <Box component="form" onSubmit={(event) => void handleSubmit(event)} noValidate>
      <Stack spacing={2} sx={{ alignItems: 'flex-start' }}>
        {toggle && (
          <FormControlLabel
            control={
              <Switch checked={switched} onChange={(event) => setSwitched(event.target.checked)} />
            }
            label={toggle.label}
          />
        )}
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          {fields.map((field) => (
            <TextField
              key={field.key}
              label={field.label}
              size="small"
              value={draft[field.key]}
              onChange={(event) => setDraft({ ...draft, [field.key]: event.target.value })}
              error={parsed[field.key] === null}
              helperText={`Erlaubt sind ${periodLabel(field.min, field.unit)} bis ${periodLabel(field.max, field.unit)}.`}
              slotProps={{ htmlInput: { inputMode: 'numeric' } }}
              sx={{ maxWidth: 280 }}
            />
          ))}
        </Stack>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
          <BusyButton
            type="submit"
            variant="outlined"
            busy={busy}
            busyAnnouncement="Frist wird gespeichert"
            disabled={(!changed || !valid) && !busy}
          >
            Speichern
          </BusyButton>
          {overridden && (
            <Button variant="text" disabled={busy} onClick={() => void run(onReset)}>
              Werkseinstellung wiederherstellen
            </Button>
          )}
        </Stack>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          {overridden
            ? `Es gilt ein eigener Wert. Werkseinstellung: ${defaultText}.`
            : 'Es gilt die Werkseinstellung.'}
        </Typography>
      </Stack>
    </Box>
  )
}
