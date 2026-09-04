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
  MetadataValueRequest,
} from '../../types/api'
import { getDocumentTypeVocabulary, setDocumentMetadataValue } from '../../services/api'
import MetadataValueForm from './MetadataValueForm'
import { isMetadataValueComplete, metadataValueRequestFor } from './metadataValues'

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
  switch (field.fieldKey) {
    case 'title':
      return { textValue: field.value ?? '' }
    case 'document_type':
      return { vocabularyCode: field.value ?? '' }
    default:
      return { dateValue: field.value ?? '', datePrecision: field.datePrecision ?? 'DAY' }
  }
}

// #1068: sets or changes one core field of one document by hand. The saved value carries the
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
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

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
    if (!isMetadataValueComplete(field.fieldKey, value)) {
      setError('Bitte einen Wert angeben.')
      return
    }
    setSubmitting(true)
    try {
      const saved = await setDocumentMetadataValue(
        libraryId,
        documentId,
        field.fieldKey,
        metadataValueRequestFor(field.fieldKey, value),
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
        {field.value != null ? `${field.label} ändern` : `${field.label} setzen`}
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Typography variant="body2" color="text.secondary">
            Dokument: {fileName}
          </Typography>
          {error && <Alert severity="error">{error}</Alert>}
          <MetadataValueForm
            fieldKey={field.fieldKey}
            value={value}
            onChange={setValue}
            vocabulary={vocabulary}
            disabled={submitting}
          />
          <Typography variant="caption" color="text.secondary">
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
          disabled={submitting || !isMetadataValueComplete(field.fieldKey, value)}
        >
          Speichern
        </Button>
      </DialogActions>
    </Dialog>
  )
}
