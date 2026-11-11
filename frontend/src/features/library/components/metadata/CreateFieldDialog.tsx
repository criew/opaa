import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import { createLibraryMetadataField } from '../../api/libraryMetadataApi'
import type { LibraryMetadataFieldType } from '@/shared/api/types'
import ChangeImpactNotice from './ChangeImpactNotice'
import { TYPE_LABELS } from './metadataFieldText'
import FormDialog from '@/shared/ui/dialog/FormDialog'

/** „Feld anlegen": key, name, type, value list or pattern and Wirkstellen. */
export default function CreateFieldDialog({
  open,
  libraryId,
  onClose,
  onCreated,
  onError,
}: {
  open: boolean
  libraryId: string
  onClose: () => void
  onCreated: () => void
  onError: (message: string) => void
}) {
  const [fieldKey, setFieldKey] = useState('')
  const [label, setLabel] = useState('')
  const [type, setType] = useState<LibraryMetadataFieldType>('SELECT')
  const [valuePattern, setValuePattern] = useState('')
  const [filter, setFilter] = useState(true)
  const [contextPrefix, setContextPrefix] = useState(false)
  const [values, setValues] = useState('')

  const noEffect = !filter && !contextPrefix

  async function submit() {
    // the warning in the form says why nothing is created
    if (noEffect) return
    try {
      await createLibraryMetadataField(libraryId, {
        fieldKey,
        label,
        type,
        valuePattern: type === 'PATTERN' ? valuePattern : null,
        filter,
        contextPrefix,
        values:
          type === 'SELECT'
            ? values
                .split('\n')
                .map((line) => line.trim())
                .filter((line) => line.length > 0)
                .map((line) => {
                  const [code, ...rest] = line.split('=')
                  return { code: code.trim(), label: (rest.join('=') || code).trim() }
                })
            : undefined,
      })
      onCreated()
    } catch (err) {
      onError(err instanceof Error ? err.message : 'Das Feld konnte nicht angelegt werden')
    }
  }

  return (
    <FormDialog
      open={open}
      title="Feld anlegen"
      submitLabel="Anlegen"
      onSubmit={() => void submit()}
      onClose={onClose}
    >
      <Stack spacing={2} sx={{ mt: 1 }}>
        <FormField
          size="small"
          label="Feldschlüssel"
          helperText="Kleinbuchstaben, Ziffern und Unterstriche, z. B. fassung"
          value={fieldKey}
          onChange={(e) => setFieldKey(e.target.value)}
        />
        <FormField
          size="small"
          label="Feldname"
          value={label}
          onChange={(e) => setLabel(e.target.value)}
        />
        <FormField
          select
          label="Typ"
          fullWidth
          size="small"
          value={type}
          onChange={(e) => setType(e.target.value as LibraryMetadataFieldType)}
        >
          {(Object.keys(TYPE_LABELS) as LibraryMetadataFieldType[]).map((option) => (
            <MenuItem key={option} value={option}>
              {TYPE_LABELS[option]}
            </MenuItem>
          ))}
        </FormField>
        {type === 'SELECT' && (
          <FormField
            size="small"
            label="Werteliste"
            helperText="Eine Zeile je Wert, Format CODE=Bezeichnung"
            multiline
            value={values}
            onChange={(e) => setValues(e.target.value)}
          />
        )}
        {type === 'PATTERN' && (
          <FormField
            size="small"
            label="Muster"
            helperText="Regulärer Ausdruck, gegen den jeder Wert geprüft wird"
            value={valuePattern}
            onChange={(e) => setValuePattern(e.target.value)}
          />
        )}
        <FormControlLabel
          control={<Checkbox checked={filter} onChange={(e) => setFilter(e.target.checked)} />}
          label="Wirkt im Filter"
        />
        <FormControlLabel
          control={
            <Checkbox
              checked={contextPrefix}
              onChange={(e) => setContextPrefix(e.target.checked)}
            />
          }
          label="Wirkt im Kontextpräfix"
        />
        {noEffect && (
          <Alert severity="warning">
            Jedes Feld muss mindestens im Filter oder im Kontextpräfix wirken.
          </Alert>
        )}
        {contextPrefix && (
          <ChangeImpactNotice libraryId={libraryId} fieldKey={fieldKey} change="FIELD_ADDED" />
        )}
      </Stack>
    </FormDialog>
  )
}
