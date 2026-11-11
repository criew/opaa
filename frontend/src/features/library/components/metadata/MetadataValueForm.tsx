import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import type {
  DatePrecision,
  DocumentTypeVocabularyEntryResponse,
  LibraryMetadataFieldResponse,
  MetadataValueRequest,
} from '@/shared/api/types'
import { datePrecisionLabel, datePrecisions } from './metadataLabels'
import { isLibraryFieldKey, isNotDeterminable } from './metadataValues'

interface MetadataValueFormProps {
  fieldKey: string
  value: MetadataValueRequest
  onChange: (value: MetadataValueRequest) => void
  vocabulary: DocumentTypeVocabularyEntryResponse[]
  /** The definition of a library field (`lib:…`); null while it is still loading. */
  libraryField?: LibraryMetadataFieldResponse | null
  disabled?: boolean
  /** Shown at the value input, e.g. when the form was sent without a value. */
  error?: string
}

// The input for one field - free text for the title, a choice from the controlled
// vocabulary for the Dokumentart (never free text: a value outside the list is not storable),
// a date plus its precision for Datum/Stand; a library field takes the input of its type. The
// third state "kein Wert ermittelbar"
// is offered here as the same operation without a value, not as a separate action. Shared by the
// single-document and the bulk dialog.
export default function MetadataValueForm({
  fieldKey,
  value,
  onChange,
  vocabulary,
  libraryField = null,
  disabled = false,
  error,
}: MetadataValueFormProps) {
  const notDeterminable = isNotDeterminable(value)
  return (
    <Stack spacing={1}>
      <ValueInput
        fieldKey={fieldKey}
        value={value}
        onChange={onChange}
        vocabulary={vocabulary}
        libraryField={libraryField}
        disabled={disabled || notDeterminable}
        error={notDeterminable ? undefined : error}
      />
      <FormControlLabel
        control={
          <Checkbox
            checked={notDeterminable}
            disabled={disabled}
            // The typed value is kept either way - only the state travels; the request body
            // drops it (metadataValueRequestFor), so an accidental click costs no retyping.
            onChange={(e) =>
              onChange({ ...value, state: e.target.checked ? 'NOT_DETERMINABLE' : 'SET' })
            }
          />
        }
        label="Kein Wert ermittelbar"
      />
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        „Kein Wert ermittelbar“ heißt: Dieses Dokument hat keinen solchen Wert. Es zählt danach
        nicht mehr zu den Dokumenten ohne Wert, und keine automatische Extraktion ändert das.
      </Typography>
    </Stack>
  )
}

function ValueInput({
  fieldKey,
  value,
  onChange,
  vocabulary,
  libraryField,
  disabled,
  error,
}: MetadataValueFormProps & { disabled: boolean }) {
  if (isLibraryFieldKey(fieldKey) && libraryField?.type !== 'DATE') {
    if (!libraryField) return null
    if (libraryField.type === 'PATTERN') {
      return (
        <FormField
          label={libraryField.label}
          value={value.textValue ?? ''}
          onChange={(e) => onChange({ ...value, textValue: e.target.value })}
          fullWidth
          required
          disabled={disabled}
          error={Boolean(error)}
          helperText={
            error ??
            (libraryField.valuePattern ? `Muster: ${libraryField.valuePattern}` : undefined)
          }
        />
      )
    }
    // A value being mapped away can no longer be set: only the current one stays, disabled.
    const selectable = libraryField.values.filter(
      (entry) => !entry.retiring || entry.code === value.textValue,
    )
    return (
      <FormField
        select
        label={libraryField.label}
        fullWidth
        required
        disabled={disabled}
        error={Boolean(error)}
        helperText={error}
        value={value.textValue ?? ''}
        onChange={(e) => onChange({ ...value, textValue: String(e.target.value) })}
      >
        {selectable.map((entry) => (
          <MenuItem key={entry.code} value={entry.code} disabled={entry.retiring}>
            {entry.retiring ? `${entry.label} (läuft aus)` : entry.label}
          </MenuItem>
        ))}
      </FormField>
    )
  }
  if (fieldKey === 'title') {
    return (
      <FormField
        label="Titel"
        value={value.textValue ?? ''}
        onChange={(e) => onChange({ ...value, textValue: e.target.value })}
        fullWidth
        required
        disabled={disabled}
        error={Boolean(error)}
        helperText={error}
        slotProps={{ htmlInput: { maxLength: 1000 } }}
      />
    )
  }
  if (fieldKey === 'document_type') {
    return (
      <FormField
        select
        label="Dokumentart"
        fullWidth
        required
        disabled={disabled}
        error={Boolean(error)}
        helperText={error}
        value={value.vocabularyCode ?? ''}
        onChange={(e) => onChange({ ...value, vocabularyCode: String(e.target.value) })}
      >
        {vocabulary.map((entry) => (
          <MenuItem key={entry.code} value={entry.code}>
            {entry.label}
          </MenuItem>
        ))}
      </FormField>
    )
  }
  return (
    <Stack direction="row" spacing={2}>
      <FormField
        label="Datum"
        type="date"
        value={value.dateValue ?? ''}
        onChange={(e) => onChange({ ...value, dateValue: e.target.value })}
        required
        disabled={disabled}
        error={Boolean(error)}
        helperText={
          error
            ? error
            : value.datePrecision === 'MONTH'
              ? 'Bei Genauigkeit „Monat" zählt nur Monat und Jahr des Datums.'
              : value.datePrecision === 'YEAR'
                ? 'Bei Genauigkeit „Jahr" zählt nur das Jahr des Datums.'
                : undefined
        }
        sx={{ flexGrow: 1 }}
      />
      <FormField
        select
        label="Genauigkeit"
        sx={{ minWidth: 160 }}
        disabled={disabled}
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
      </FormField>
    </Stack>
  )
}
