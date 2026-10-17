import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Divider from '@mui/material/Divider'
import FormControl from '@mui/material/FormControl'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { LibraryMetadataFieldResponse } from '@/shared/api/types'
import ChangeImpactNotice from './ChangeImpactNotice'

interface RemapValueDialogProps {
  libraryId: string
  /** The field whose value is edited; the dialog is open while it is set. */
  remapField: LibraryMetadataFieldResponse | null
  remapCode: string | null
  /** Documents carrying the value, null while it is still being determined. */
  remapUsage: number | null
  remapTarget: string
  valueLabel: string
  onValueLabelChange: (label: string) => void
  onRemapTargetChange: (target: string) => void
  onSaveValueLabel: () => void
  onConfirm: () => void
  onClose: () => void
}

/**
 * „Wert bearbeiten": relabel a value, or remove it together with a confirmed mapping whose
 * Folgekosten stand beforehand.
 */
export default function RemapValueDialog({
  libraryId,
  remapField,
  remapCode,
  remapUsage,
  remapTarget,
  valueLabel,
  onValueLabelChange,
  onRemapTargetChange,
  onSaveValueLabel,
  onConfirm,
  onClose,
}: RemapValueDialogProps) {
  return (
    <Dialog open={remapField != null} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Wert bearbeiten</DialogTitle>
      <DialogContent>
        <Stack direction="row" spacing={1} sx={{ mb: 2, mt: 1, alignItems: 'flex-start' }}>
          <TextField
            size="small"
            fullWidth
            label="Bezeichnung"
            helperText="Der Code bleibt; eine Bezeichnung zu korrigieren hat keine Rückwirkung."
            value={valueLabel}
            onChange={(e) => onValueLabelChange(e.target.value)}
          />
          <Button onClick={onSaveValueLabel}>Bezeichnung speichern</Button>
        </Stack>
        <Divider sx={{ mb: 2 }} />
        <Typography variant="body2" sx={{ mb: 2 }}>
          {remapUsage == null
            ? 'Betroffene Dokumente werden ermittelt …'
            : `${remapUsage} Dokument(e) tragen „${remapCode}“. Der Wert wird nur zusammen mit einer Abbildung entfernt.`}
        </Typography>
        <FormControl fullWidth size="small">
          <InputLabel id="remap-target-label">Abbilden auf</InputLabel>
          <Select
            labelId="remap-target-label"
            label="Abbilden auf"
            value={remapTarget}
            onChange={(e) => onRemapTargetChange(e.target.value)}
          >
            <MenuItem value="">leer (kein Wert)</MenuItem>
            {(remapField?.values ?? [])
              .filter((value) => value.code !== remapCode)
              .map((value) => (
                <MenuItem key={value.code} value={value.code}>
                  {value.label}
                </MenuItem>
              ))}
          </Select>
        </FormControl>
        {remapField && remapCode && (
          <ChangeImpactNotice
            libraryId={libraryId}
            fieldKey={remapField.fieldKey}
            change="VALUE_REMOVED"
            valueCode={remapCode}
          />
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Abbrechen</Button>
        <Button variant="contained" disabled={remapUsage == null} onClick={onConfirm}>
          Abbildung bestätigen
        </Button>
      </DialogActions>
    </Dialog>
  )
}
