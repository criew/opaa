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
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type {
  LlmModelResponse,
  LlmModelToolCapabilityRequest,
  ToolCapability,
  ToolCapabilityBasis,
} from '@/shared/api/types'
import { fontFamily, fontSize } from '@/shared/theme/tokens'
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
  /** A measured result to record, e.g. from „Werkzeuge prüfen“; the dialog opens with it chosen. */
  preset?: { value: ToolCapability; measurementRun: string }
}

const ORDER: ToolCapability[] = ['UNVERIFIED', 'CAPABLE', 'NOT_CAPABLE']

const MEASUREMENT_RUN_MAX_LENGTH = 300

/**
 * Records whether the model calls tools reliably — for this endpoint and this runtime, either set
 * by hand or measured with the run's identifier. „Ungeprüft“ resets the statement. The request
 * names the combination the dialog shows, so a statement for an address changed meanwhile is
 * refused (409) instead of landing on the new one. Saving is disabled while nothing changed, and
 * replacing a measured statement is announced before it happens.
 */
export default function ToolCapabilityDialog({
  model,
  open,
  onClose,
  onSaved,
  preset,
}: ToolCapabilityDialogProps) {
  const setToolCapability = useLlmModelStore((s) => s.setToolCapability)
  const stored = model.toolCapability
  const [value, setValue] = useState<ToolCapability>(preset?.value ?? stored.value)
  const [basis, setBasis] = useState<ToolCapabilityBasis>(
    preset ? 'MEASURED' : (stored.basis ?? 'MANUAL'),
  )
  const [measurementRun, setMeasurementRun] = useState(
    preset?.measurementRun ?? stored.measurementRun ?? '',
  )
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const titleId = useId()
  const choiceId = useId()
  const basisId = useId()
  const current = toolCapabilityBasisLine(stored)

  const run = measurementRun.trim()
  const unchanged =
    value === stored.value &&
    (value === 'UNVERIFIED' ||
      (basis === stored.basis && (basis === 'MANUAL' || run === (stored.measurementRun ?? ''))))
  const runMissing = value !== 'UNVERIFIED' && basis === 'MEASURED' && run === ''
  const replacesMeasured = stored.basis === 'MEASURED' && !unchanged

  // A measurement run belongs to the measured value; switching the value drops it.
  function chooseValue(next: ToolCapability) {
    setValue(next)
    setMeasurementRun(next === stored.value ? (stored.measurementRun ?? '') : '')
  }

  function request(): LlmModelToolCapabilityRequest {
    const combination = { baseUrl: model.baseUrl, modelIdentifier: model.modelIdentifier }
    if (value === 'UNVERIFIED') return { ...combination, value }
    return basis === 'MEASURED'
      ? { ...combination, value, basis, measurementRun: run }
      : { ...combination, value, basis }
  }

  async function save() {
    setSaving(true)
    setError(null)
    try {
      await setToolCapability(model.id, request())
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
        <Typography sx={{ fontSize: fontSize.text, mb: 1.5 }}>
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
          <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary', mb: 1.5 }}>
            Bisher: {TOOL_CAPABILITY_LABELS[model.toolCapability.value]}, {current}
          </Typography>
        )}
        <FormControl>
          <FormLabel id={choiceId} sx={{ fontSize: fontSize.text, mb: 0.5 }}>
            Ruft das Modell Werkzeuge zuverlässig auf?
          </FormLabel>
          <RadioGroup
            aria-labelledby={choiceId}
            value={value}
            onChange={(e) => chooseValue(e.target.value as ToolCapability)}
          >
            {ORDER.map((key) => (
              <FormControlLabel
                key={key}
                value={key}
                control={<Radio size="small" />}
                sx={{ alignItems: 'flex-start', mb: 0.5, '& .MuiRadio-root': { pt: 0.25 } }}
                label={
                  <Box>
                    <Typography sx={{ fontSize: fontSize.text, fontWeight: 500 }}>
                      {TOOL_CAPABILITY_LABELS[key]}
                    </Typography>
                    <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
                      {TOOL_CAPABILITY_HINTS[key]}
                    </Typography>
                  </Box>
                }
              />
            ))}
          </RadioGroup>
        </FormControl>
        {value !== 'UNVERIFIED' && (
          <FormControl sx={{ display: 'block', mt: 1.5 }}>
            <FormLabel id={basisId} sx={{ fontSize: fontSize.text, mb: 0.5 }}>
              Grundlage
            </FormLabel>
            <RadioGroup
              row
              aria-labelledby={basisId}
              value={basis}
              onChange={(e) => setBasis(e.target.value as ToolCapabilityBasis)}
            >
              <FormControlLabel
                value="MEASURED"
                control={<Radio size="small" />}
                label={<Typography sx={{ fontSize: fontSize.text }}>Gemessen</Typography>}
              />
              <FormControlLabel
                value="MANUAL"
                control={<Radio size="small" />}
                label={<Typography sx={{ fontSize: fontSize.text }}>Manuell gesetzt</Typography>}
              />
            </RadioGroup>
            {basis === 'MEASURED' && (
              <TextField
                label="Messlauf"
                required
                fullWidth
                size="small"
                value={measurementRun}
                onChange={(e) => setMeasurementRun(e.target.value)}
                slotProps={{ htmlInput: { maxLength: MEASUREMENT_RUN_MAX_LENGTH } }}
                helperText="Kennung des Messlaufs aus „Werkzeuge prüfen“ oder aus dem Kopf des Berichts tool-calling.md."
                sx={{ mt: 1 }}
              />
            )}
            <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary', mt: 1 }}>
              Die Angabe trägt das heutige Datum. Wer sie ändert, steht im Protokoll, nicht am
              Modell.
            </Typography>
          </FormControl>
        )}
        {replacesMeasured && (
          <Alert severity="warning" sx={{ mt: 2 }}>
            Die bisherige Angabe ist gemessen ({current}). Speichern ersetzt sie; der Messlauf steht
            danach nur noch im Protokoll.
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Abbrechen</Button>
        <Button
          variant="contained"
          onClick={() => void save()}
          disabled={saving || unchanged || runMissing}
        >
          {saving
            ? 'Wird gespeichert …'
            : replacesMeasured
              ? 'Gemessene Angabe ersetzen'
              : 'Speichern'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
