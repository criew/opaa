import { useId, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormLabel from '@mui/material/FormLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Typography from '@mui/material/Typography'
import type { LlmModelResponse, ToolCapability } from '@/shared/api/types'
import { fontFamily } from '@/shared/theme/tokens'
import { useLlmModelStore } from '../stores/llmModelStore'
import {
  TOOL_CAPABILITY_HINTS,
  TOOL_CAPABILITY_LABELS,
  toolCapabilityBasisLine,
} from './toolCapability'

interface ToolCapabilityDialogProps {
  model: LlmModelResponse
  open: boolean
  onClose: () => void
  onSaved: (message: string) => void
}

const ORDER: ToolCapability[] = ['UNVERIFIED', 'CAPABLE', 'NOT_CAPABLE']

/**
 * Records whether the model calls tools reliably — for this endpoint and this runtime. The
 * dialog sets the statement by hand (basis „manuell gesetzt“); a measured statement with its
 * measurement run comes through the administration API. „Ungeprüft“ resets the statement.
 */
export default function ToolCapabilityDialog({
  model,
  open,
  onClose,
  onSaved,
}: ToolCapabilityDialogProps) {
  const setToolCapability = useLlmModelStore((s) => s.setToolCapability)
  const [value, setValue] = useState<ToolCapability>(model.toolCapability.value)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const titleId = useId()
  const choiceId = useId()
  const current = toolCapabilityBasisLine(model.toolCapability)

  async function save() {
    setSaving(true)
    setError(null)
    try {
      await setToolCapability(
        model.id,
        value === 'UNVERIFIED' ? { value } : { value, basis: 'MANUAL' },
      )
      onSaved(`Werkzeugfähigkeit von „${model.displayName}“: ${TOOL_CAPABILITY_LABELS[value]}.`)
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Speichern fehlgeschlagen')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth aria-labelledby={titleId}>
      <DialogTitle id={titleId}>Werkzeugfähigkeit von „{model.displayName}“</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Typography sx={{ fontSize: 13.5, mb: 1.5 }}>
          Die Angabe gilt für diesen Endpunkt und diese Laufzeit geprüft:{' '}
          <Box component="span" sx={{ fontFamily: fontFamily.mono, overflowWrap: 'anywhere' }}>
            {model.modelIdentifier}
          </Box>{' '}
          an{' '}
          <Box component="span" sx={{ fontFamily: fontFamily.mono, overflowWrap: 'anywhere' }}>
            {model.baseUrl}
          </Box>
          . Ändern sich Basis-Adresse oder Modell-Kennung, ist sie wieder ungeprüft.
        </Typography>
        {current && (
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 1.5 }}>
            Bisher: {TOOL_CAPABILITY_LABELS[model.toolCapability.value]}, {current}
          </Typography>
        )}
        <FormControl>
          <FormLabel id={choiceId} sx={{ fontSize: 13.5, mb: 0.5 }}>
            Ruft das Modell Werkzeuge zuverlässig auf?
          </FormLabel>
          <RadioGroup
            aria-labelledby={choiceId}
            value={value}
            onChange={(e) => setValue(e.target.value as ToolCapability)}
          >
            {ORDER.map((key) => (
              <FormControlLabel
                key={key}
                value={key}
                control={<Radio size="small" />}
                sx={{ alignItems: 'flex-start', mb: 0.5, '& .MuiRadio-root': { pt: 0.25 } }}
                label={
                  <Box>
                    <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>
                      {TOOL_CAPABILITY_LABELS[key]}
                    </Typography>
                    <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                      {TOOL_CAPABILITY_HINTS[key]}
                    </Typography>
                  </Box>
                }
              />
            ))}
          </RadioGroup>
        </FormControl>
        {value !== 'UNVERIFIED' && (
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
            Grundlage: manuell gesetzt, mit dem heutigen Datum. Wer die Angabe ändert, steht im
            Protokoll, nicht am Modell.
          </Typography>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Abbrechen</Button>
        <Button variant="contained" onClick={() => void save()} disabled={saving}>
          {saving ? 'Wird gespeichert …' : 'Speichern'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
