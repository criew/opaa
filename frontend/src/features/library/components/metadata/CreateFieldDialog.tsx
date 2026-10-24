import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { createLibraryMetadataField } from '../../api/libraryMetadataApi'
import type { LibraryMetadataFieldType } from '@/shared/api/types'
import ChangeImpactNotice from './ChangeImpactNotice'
import { TYPE_LABELS } from './metadataFieldText'

/** „Feld anlegen": key, name, type, value list or pattern, Wirkstellen and citation position. */
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
  const [citationPosition, setCitationPosition] = useState('')
  const [values, setValues] = useState('')

  const noEffect = !filter && !contextPrefix

  async function submit() {
    try {
      await createLibraryMetadataField(libraryId, {
        fieldKey,
        label,
        type,
        valuePattern: type === 'PATTERN' ? valuePattern : null,
        filter,
        contextPrefix,
        citationPosition: citationPosition === '' ? null : Number(citationPosition),
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
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Metadatenfeld anlegen</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {/* value lists are visible to every reader of the library - said where they are made */}
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
            Wertelisten sieht jede Person, die die Bibliothek benutzt – keine schutzbedürftigen
            Bezeichnungen.
          </Typography>
          <TextField
            size="small"
            label="Feldschlüssel"
            helperText="Kleinbuchstaben, Ziffern und Unterstriche, z. B. fassung"
            value={fieldKey}
            onChange={(e) => setFieldKey(e.target.value)}
          />
          <TextField
            size="small"
            label="Feldname"
            value={label}
            onChange={(e) => setLabel(e.target.value)}
          />
          <FormControl fullWidth size="small">
            <InputLabel id="field-type-label">Typ</InputLabel>
            <Select
              labelId="field-type-label"
              label="Typ"
              value={type}
              onChange={(e) => setType(e.target.value as LibraryMetadataFieldType)}
            >
              {(Object.keys(TYPE_LABELS) as LibraryMetadataFieldType[]).map((option) => (
                <MenuItem key={option} value={option}>
                  {TYPE_LABELS[option]}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
          {type === 'SELECT' && (
            <TextField
              size="small"
              label="Werteliste"
              helperText="Eine Zeile je Wert, Format CODE=Bezeichnung"
              multiline
              minRows={3}
              value={values}
              onChange={(e) => setValues(e.target.value)}
            />
          )}
          {type === 'PATTERN' && (
            <TextField
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
          <FormControl fullWidth size="small">
            <InputLabel id="citation-position-label">Zitierposition</InputLabel>
            <Select
              labelId="citation-position-label"
              label="Zitierposition"
              value={citationPosition}
              onChange={(e) => setCitationPosition(e.target.value)}
            >
              <MenuItem value="">nicht in der Belegzeile</MenuItem>
              <MenuItem value="1">1</MenuItem>
              <MenuItem value="2">2</MenuItem>
            </Select>
          </FormControl>
          {noEffect && (
            <Alert severity="warning">
              Jedes Feld muss mindestens im Filter oder im Kontextpräfix wirken; „nur Beleg-Anzeige“
              genügt nicht.
            </Alert>
          )}
          {contextPrefix && (
            <ChangeImpactNotice libraryId={libraryId} fieldKey={fieldKey} change="FIELD_ADDED" />
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Abbrechen</Button>
        <Button variant="contained" disabled={noEffect} onClick={() => void submit()}>
          Anlegen
        </Button>
      </DialogActions>
    </Dialog>
  )
}
