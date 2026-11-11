import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import MenuItem from '@mui/material/MenuItem'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import type { LibraryMetadataFieldResponse } from '@/shared/api/types'
import ChangeImpactNotice from './ChangeImpactNotice'
import FormDialog from '@/shared/ui/dialog/FormDialog'

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
    <FormDialog
      open={remapField != null}
      title="Wert bearbeiten"
      submitLabel="Abbilden"
      onSubmit={() => {
        // the consequence is not known yet: nothing to confirm
        if (remapUsage != null) onConfirm()
      }}
      onClose={onClose}
    >
      <FormField
        size="small"
        fullWidth
        label="Bezeichnung"
        helperText="Der Code bleibt; eine Bezeichnung zu korrigieren hat keine Rückwirkung."
        value={valueLabel}
        onChange={(e) => onValueLabelChange(e.target.value)}
        // Enter in this field saves the label; it must not submit the mapping below
        onKeyDown={(e) => {
          if (e.key !== 'Enter') return
          e.preventDefault()
          onSaveValueLabel()
        }}
        action={
          <Button variant="outlined" onClick={onSaveValueLabel} sx={{ flex: 'none' }}>
            Bezeichnung speichern
          </Button>
        }
        sx={{ mb: 2, mt: 1 }}
      />
      <Divider sx={{ mb: 2 }} />
      <Typography variant="body2" sx={{ mb: 2 }}>
        {remapUsage == null
          ? 'Betroffene Dokumente werden ermittelt …'
          : `${remapUsage} Dokument(e) tragen „${remapCode}“. Der Wert wird nur zusammen mit einer Abbildung entfernt.`}
      </Typography>
      <FormField
        select
        label="Abbilden auf"
        fullWidth
        size="small"
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
      </FormField>
      {remapField && remapCode && (
        <ChangeImpactNotice
          libraryId={libraryId}
          fieldKey={remapField.fieldKey}
          change="VALUE_REMOVED"
          valueCode={remapCode}
        />
      )}
    </FormDialog>
  )
}
