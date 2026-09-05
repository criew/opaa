import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Divider from '@mui/material/Divider'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import Checkbox from '@mui/material/Checkbox'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import {
  addLibraryMetadataFieldValue,
  createLibraryMetadataField,
  deleteLibraryMetadataField,
  getLibraryMetadataFieldUsage,
  getLibraryMetadataFieldValueUsage,
  listLibraryMetadataFields,
  remapLibraryMetadataFieldValue,
} from '../../services/api'
import type { LibraryMetadataFieldResponse, LibraryMetadataFieldType } from '../../types/api'

const TYPE_LABELS: Record<LibraryMetadataFieldType, string> = {
  SELECT: 'Auswahl aus einer Werteliste',
  DATE: 'Jahr oder Datum',
  PATTERN: 'Kennung nach Muster',
}

/** The Wirkstellen of a field as short chips - what the Aufnahmeregel makes visible. */
function effectChips(field: LibraryMetadataFieldResponse) {
  const chips: string[] = []
  if (field.filter) chips.push('Filter')
  if (field.contextPrefix) chips.push('Kontextpräfix')
  if (field.citationPosition != null) chips.push(`Beleg ${field.citationPosition}`)
  return chips
}

interface Props {
  libraryId: string
  /** Only a person with the management right at the library may change the schema (#1071). */
  canManageSchema: boolean
  onFieldsChanged?: () => void
}

/**
 * The "Metadatenfelder" section of a library's settings (#1071, metadata-schema.md Teil V): the
 * library's own fields with their Wirkstellen and value lists, and the two operations the
 * specification guards - a field is only accepted with a retrieval effect, and a value is only
 * removed together with a confirmed mapping whose Folgekosten stand beforehand.
 */
export default function LibraryMetadataFieldsSection({
  libraryId,
  canManageSchema,
  onFieldsChanged,
}: Props) {
  const [fields, setFields] = useState<LibraryMetadataFieldResponse[]>([])
  const [error, setError] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [remapField, setRemapField] = useState<LibraryMetadataFieldResponse | null>(null)
  const [remapCode, setRemapCode] = useState<string | null>(null)
  const [remapUsage, setRemapUsage] = useState<number | null>(null)
  const [remapTarget, setRemapTarget] = useState<string>('')
  const [deleteField, setDeleteField] = useState<LibraryMetadataFieldResponse | null>(null)
  const [deleteUsage, setDeleteUsage] = useState<number | null>(null)

  const reload = useCallback(async () => {
    try {
      const response = await listLibraryMetadataFields(libraryId)
      setFields(response.items)
      setError(null)
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Die Metadatenfelder konnten nicht geladen werden',
      )
    }
  }, [libraryId])

  useEffect(() => {
    let cancelled = false
    listLibraryMetadataFields(libraryId)
      .then((response) => {
        if (cancelled) return
        setFields(response.items)
        setError(null)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setError(
          err instanceof Error ? err.message : 'Die Metadatenfelder konnten nicht geladen werden',
        )
      })
    return () => {
      cancelled = true
    }
  }, [libraryId])

  async function openRemap(field: LibraryMetadataFieldResponse, code: string) {
    setRemapField(field)
    setRemapCode(code)
    setRemapTarget('')
    setRemapUsage(null)
    try {
      const usage = await getLibraryMetadataFieldValueUsage(libraryId, field.fieldKey, code)
      setRemapUsage(usage.documentCount)
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Die Folgekosten konnten nicht ermittelt werden',
      )
    }
  }

  async function confirmRemap() {
    if (!remapField || !remapCode) return
    try {
      await remapLibraryMetadataFieldValue(
        libraryId,
        remapField.fieldKey,
        remapCode,
        remapTarget === '' ? null : remapTarget,
      )
      setRemapField(null)
      setRemapCode(null)
      await reload()
      onFieldsChanged?.()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Abbildung ist fehlgeschlagen')
    }
  }

  async function openDelete(field: LibraryMetadataFieldResponse) {
    setDeleteField(field)
    setDeleteUsage(null)
    try {
      const usage = await getLibraryMetadataFieldUsage(libraryId, field.fieldKey)
      setDeleteUsage(usage.documentCount)
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Die Folgekosten konnten nicht ermittelt werden',
      )
    }
  }

  async function confirmDelete() {
    if (!deleteField) return
    try {
      await deleteLibraryMetadataField(libraryId, deleteField.fieldKey)
      setDeleteField(null)
      await reload()
      onFieldsChanged?.()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Das Feld konnte nicht gelöscht werden')
    }
  }

  return (
    <Box>
      <Stack direction="row" sx={{ mb: 1, alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="subtitle1">Metadatenfelder</Typography>
        {canManageSchema && (
          <Button size="small" onClick={() => setCreateOpen(true)}>
            Feld anlegen
          </Button>
        )}
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Eigene Felder dieser Bibliothek, zusätzlich zu Titel, Dokumentart und Datum/Stand. Jedes
        Feld muss im Filter oder im Kontextpräfix wirken; höchstens fünf Felder je Bibliothek und
        höchstens zwei davon in der Belegzeile.
      </Typography>
      <Alert severity="info" sx={{ mb: 2 }}>
        Wertelisten sind für jede Person sichtbar, die die Bibliothek benutzen darf. Sie dürfen
        deshalb keine schutzbedürftigen Bezeichnungen tragen.
      </Alert>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      {fields.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          Diese Bibliothek führt keine eigenen Metadatenfelder — der Normalfall.
        </Typography>
      ) : (
        <Stack spacing={2} divider={<Divider flexItem />}>
          {fields.map((field) => (
            <Box key={field.fieldKey}>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                <Typography variant="subtitle2">{field.label}</Typography>
                <Typography variant="caption" color="text.secondary">
                  {field.fieldKey} · {TYPE_LABELS[field.type]}
                </Typography>
                {effectChips(field).map((chip) => (
                  <Chip key={chip} size="small" label={chip} />
                ))}
                {canManageSchema && (
                  <Button size="small" color="error" onClick={() => void openDelete(field)}>
                    Löschen
                  </Button>
                )}
              </Stack>
              {field.valuePattern && (
                <Typography variant="caption" color="text.secondary">
                  Muster: {field.valuePattern}
                </Typography>
              )}
              {field.type === 'SELECT' && (
                <Stack direction="row" spacing={1} sx={{ mt: 1, flexWrap: 'wrap' }}>
                  {field.values.map((value) => (
                    <Chip
                      key={value.code}
                      size="small"
                      variant="outlined"
                      label={`${value.label} (${value.code})`}
                      onDelete={
                        canManageSchema ? () => void openRemap(field, value.code) : undefined
                      }
                      deleteIcon={
                        <span role="button" aria-label={`Wert ${value.label} entfernen`}>
                          ×
                        </span>
                      }
                    />
                  ))}
                  {canManageSchema && (
                    <AddValueButton
                      libraryId={libraryId}
                      fieldKey={field.fieldKey}
                      onAdded={() => void reload()}
                      onError={setError}
                    />
                  )}
                </Stack>
              )}
            </Box>
          ))}
        </Stack>
      )}

      <CreateFieldDialog
        open={createOpen}
        libraryId={libraryId}
        onClose={() => setCreateOpen(false)}
        onCreated={() => {
          setCreateOpen(false)
          void reload()
          onFieldsChanged?.()
        }}
        onError={setError}
      />

      <Dialog open={remapField != null} onClose={() => setRemapField(null)} fullWidth maxWidth="sm">
        <DialogTitle>Wert entfernen</DialogTitle>
        <DialogContent>
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
              onChange={(e) => setRemapTarget(e.target.value)}
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
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRemapField(null)}>Abbrechen</Button>
          <Button
            variant="contained"
            disabled={remapUsage == null}
            onClick={() => void confirmRemap()}
          >
            Abbildung bestätigen
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={deleteField != null} onClose={() => setDeleteField(null)}>
        <DialogTitle>Feld löschen</DialogTitle>
        <DialogContent>
          <Typography variant="body2">
            {deleteUsage == null
              ? 'Betroffene Dokumente werden ermittelt …'
              : `${deleteUsage} Dokument(e) tragen einen Wert für „${deleteField?.label}“. Mit dem Feld werden diese Werte und seine Werteliste entfernt.`}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteField(null)}>Abbrechen</Button>
          <Button
            color="error"
            variant="contained"
            disabled={deleteUsage == null}
            onClick={() => void confirmDelete()}
          >
            Endgültig löschen
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}

function AddValueButton({
  libraryId,
  fieldKey,
  onAdded,
  onError,
}: {
  libraryId: string
  fieldKey: string
  onAdded: () => void
  onError: (message: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [code, setCode] = useState('')
  const [label, setLabel] = useState('')

  async function submit() {
    try {
      await addLibraryMetadataFieldValue(libraryId, fieldKey, { code, label })
      setOpen(false)
      setCode('')
      setLabel('')
      onAdded()
    } catch (err) {
      onError(err instanceof Error ? err.message : 'Der Wert konnte nicht ergänzt werden')
    }
  }

  return (
    <>
      <Button size="small" onClick={() => setOpen(true)}>
        Wert ergänzen
      </Button>
      <Dialog open={open} onClose={() => setOpen(false)}>
        <DialogTitle>Wert ergänzen</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              size="small"
              label="Code"
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
            <TextField
              size="small"
              label="Bezeichnung"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Abbrechen</Button>
          <Button variant="contained" onClick={() => void submit()}>
            Ergänzen
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}

function CreateFieldDialog({
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
