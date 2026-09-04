import FormControl from '@mui/material/FormControl'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import type {
  DatePrecision,
  DocumentTypeVocabularyEntryResponse,
  MetadataValueRequest,
} from '../../types/api'
import { datePrecisionLabel, datePrecisions } from '../../utils/labels'

interface MetadataValueFormProps {
  fieldKey: string
  value: MetadataValueRequest
  onChange: (value: MetadataValueRequest) => void
  vocabulary: DocumentTypeVocabularyEntryResponse[]
  disabled?: boolean
}

// #1068: the input for one core field - free text for the title, a choice from the controlled
// vocabulary for the Dokumentart (never free text: a value outside the list is not storable),
// a date plus its precision for Datum/Stand. Shared by the single-document and the bulk dialog.
export default function MetadataValueForm({
  fieldKey,
  value,
  onChange,
  vocabulary,
  disabled = false,
}: MetadataValueFormProps) {
  if (fieldKey === 'title') {
    return (
      <TextField
        label="Titel"
        value={value.textValue ?? ''}
        onChange={(e) => onChange({ ...value, textValue: e.target.value })}
        fullWidth
        required
        disabled={disabled}
        slotProps={{ htmlInput: { maxLength: 1000 } }}
      />
    )
  }
  if (fieldKey === 'document_type') {
    return (
      <FormControl fullWidth required disabled={disabled}>
        <InputLabel id="metadata-document-type-label">Dokumentart</InputLabel>
        <Select
          labelId="metadata-document-type-label"
          label="Dokumentart"
          value={value.vocabularyCode ?? ''}
          onChange={(e) => onChange({ ...value, vocabularyCode: String(e.target.value) })}
        >
          {vocabulary.map((entry) => (
            <MenuItem key={entry.code} value={entry.code}>
              {entry.label}
            </MenuItem>
          ))}
        </Select>
      </FormControl>
    )
  }
  return (
    <Stack direction="row" spacing={2}>
      <TextField
        label="Datum"
        type="date"
        value={value.dateValue ?? ''}
        onChange={(e) => onChange({ ...value, dateValue: e.target.value })}
        required
        disabled={disabled}
        slotProps={{ inputLabel: { shrink: true } }}
        sx={{ flexGrow: 1 }}
      />
      <FormControl sx={{ minWidth: 160 }} disabled={disabled}>
        <InputLabel id="metadata-date-precision-label">Genauigkeit</InputLabel>
        <Select
          labelId="metadata-date-precision-label"
          label="Genauigkeit"
          value={value.datePrecision ?? 'DAY'}
          onChange={(e) =>
            onChange({ ...value, datePrecision: String(e.target.value) as DatePrecision })
          }
        >
          {datePrecisions.map((precision) => (
            <MenuItem key={precision} value={precision}>
              {datePrecisionLabel(precision)}
            </MenuItem>
          ))}
        </Select>
      </FormControl>
    </Stack>
  )
}
