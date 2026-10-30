import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'

interface ShareCapSwitchProps {
  label: string
  description: string
  checked: boolean
  onSave: (checked: boolean) => Promise<void>
}

/**
 * The upper bound the system administration sets for a connector library: whether it may be
 * shared with „Alle Konten". Visible and settable only here, never for the library's owner:
 * they see the effect (a release above the bound answers 409), not the switch.
 */
export default function ShareCapSwitch({
  label,
  description,
  checked,
  onSave,
}: ShareCapSwitchProps) {
  const [draft, setDraft] = useState(checked)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const changed = draft !== checked
  const buttonText = saving ? 'Wird gespeichert …' : 'Obergrenze speichern'

  async function handleSave() {
    setError(null)
    setSaving(true)
    try {
      await onSave(draft)
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Freigabe-Obergrenze konnte nicht geändert werden',
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <Box sx={{ pt: 1, borderTop: '1px solid', borderColor: 'divider' }}>
      <Typography variant="subtitle2" component="h3">
        Obergrenze (Systemverwaltung)
      </Typography>
      <Typography variant="caption" component="p" sx={{ color: 'text.secondary', mb: 1 }}>
        {description}
      </Typography>
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <FormControlLabel
          control={<Checkbox checked={draft} onChange={(e) => setDraft(e.target.checked)} />}
          label={label}
        />
        <Button
          size="small"
          variant="outlined"
          disabled={saving || !changed}
          // Both halves of the upper bound are on the same page; the visible text is
          // the same, so the accessible name adds which half is saved after it.
          aria-label={`${buttonText}: ${label}`}
          onClick={() => void handleSave()}
        >
          {buttonText}
        </Button>
      </Stack>
      {error && (
        <Alert severity="error" sx={{ mt: 1 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
    </Box>
  )
}
