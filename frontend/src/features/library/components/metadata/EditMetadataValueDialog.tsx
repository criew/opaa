import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type {
  DocumentMetadataFieldResponse,
  DocumentTypeVocabularyEntryResponse,
  LibraryMetadataFieldResponse,
  MetadataValueRequest,
} from '@/shared/api/types'
import {
  getDocumentTypeVocabulary,
  listLibraryMetadataFields,
  setDocumentMetadataValue,
} from '../../api/libraryMetadataApi'
import MetadataValueForm from './MetadataValueForm'
import {
  isLibraryFieldKey,
  isMetadataValueComplete,
  metadataValueRequestFor,
} from './metadataValues'

interface EditMetadataValueDialogProps {
  open: boolean
  onClose: () => void
  libraryId: string
  documentId: string
  fileName: string
  field: DocumentMetadataFieldResponse
  onSaved: (field: DocumentMetadataFieldResponse) => void
}

function initialValueOf(field: DocumentMetadataFieldResponse): MetadataValueRequest {
  if (field.state === 'NOT_DETERMINABLE') return { state: 'NOT_DETERMINABLE' }
  if (isLibraryFieldKey(field.fieldKey)) {
    // The type arrives with the field definition; only a DATE value carries a precision.
    const isDate = field.datePrecision != null
    return {
      textValue: isDate ? '' : (field.value ?? ''),
      dateValue: isDate ? (field.value ?? '') : '',
      datePrecision: field.datePrecision ?? 'DAY',
    }
  }
  switch (field.fieldKey) {
    case 'title':
      return { textValue: field.value ?? '' }
    case 'document_type':
      return { vocabularyCode: field.value ?? '' }
    default:
      return { dateValue: field.value ?? '', datePrecision: field.datePrecision ?? 'DAY' }
  }
}

// Sets or changes one core or library field of one document by hand. The saved value carries the
// origin "manuell" and the calling person; no automatic extraction overwrites it afterwards.
export default function EditMetadataValueDialog({
  open,
  onClose,
  libraryId,
  documentId,
  fileName,
  field,
  onSaved,
}: EditMetadataValueDialogProps) {
  const [value, setValue] = useState<MetadataValueRequest>(() => initialValueOf(field))
  const [vocabulary, setVocabulary] = useState<DocumentTypeVocabularyEntryResponse[]>([])
  const [libraryField, setLibraryField] = useState<LibraryMetadataFieldResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!open || !isLibraryFieldKey(field.fieldKey)) return
    let cancelled = false
    listLibraryMetadataFields(libraryId)
      .then((response) => {
        if (cancelled) return
        const definition = response.items.find((item) => item.documentFieldKey === field.fieldKey)
        if (definition) setLibraryField(definition)
        else setError('Das Feld gibt es in dieser Bibliothek nicht mehr.')
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Das Feld konnte nicht geladen werden')
        }
      })
    return () => {
      cancelled = true
    }
  }, [open, libraryId, field.fieldKey])

  useEffect(() => {
    if (!open || field.fieldKey !== 'document_type') return
    let cancelled = false
    getDocumentTypeVocabulary()
      .then((response) => {
        if (!cancelled) setVocabulary(response.items)
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(
            err instanceof Error ? err.message : 'Dokumentarten konnten nicht geladen werden',
          )
        }
      })
    return () => {
      cancelled = true
    }
  }, [open, field.fieldKey])

  function handleClose() {
    if (submitting) return
    onClose()
  }

  async function handleSave() {
    setError(null)
    if (!isMetadataValueComplete(field.fieldKey, value, libraryField)) {
      setError('Bitte einen Wert angeben.')
      return
    }
    setSubmitting(true)
    try {
      const saved = await setDocumentMetadataValue(
        libraryId,
        documentId,
        field.fieldKey,
        metadataValueRequestFor(field.fieldKey, value, libraryField),
      )
      onSaved(saved)
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Wert konnte nicht gespeichert werden')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        {field.state !== 'EMPTY' ? `${field.label} ändern` : `${field.label} setzen`}
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            Dokument: {fileName}
          </Typography>
          {error && <Alert severity="error">{error}</Alert>}
          <MetadataValueForm
            fieldKey={field.fieldKey}
            value={value}
            onChange={setValue}
            vocabulary={vocabulary}
            libraryField={libraryField}
            disabled={submitting}
          />
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Ein von Hand gesetzter Wert wird als „manuell" gekennzeichnet und von keiner
            automatischen Extraktion überschrieben.
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose} disabled={submitting}>
          Abbrechen
        </Button>
        <Button
          variant="contained"
          onClick={() => void handleSave()}
          disabled={submitting || !isMetadataValueComplete(field.fieldKey, value, libraryField)}
        >
          Speichern
        </Button>
      </DialogActions>
    </Dialog>
  )
}
