import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControl from '@mui/material/FormControl'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type {
  BulkMetadataValueResponse,
  DocumentTypeVocabularyEntryResponse,
  MetadataValueRequest,
} from '../../types/api'
import { bulkSetDocumentMetadata, getDocumentTypeVocabulary } from '../../services/api'
import MetadataValueForm from './MetadataValueForm'
import {
  CORE_METADATA_FIELDS,
  coreMetadataFieldLabel,
  describeMetadataValue,
  isMetadataValueComplete,
  metadataValueRequestFor,
} from './metadataValues'

interface BulkMetadataDialogProps {
  open: boolean
  onClose: () => void
  libraryId: string
  documentIds: string[]
  onDone: (result: BulkMetadataValueResponse) => void
}

type Step = 'form' | 'confirm' | 'result'

function documentCountLabel(count: number): string {
  return `${count} ${count === 1 ? 'Dokument' : 'Dokumente'}`
}

// #1068: Sammelzuweisung - one field, one value, the person's own selection of documents. The
// decision is taken once and confirmed with the count; every document still gets its own
// "manuell" value and its own audit event.
export default function BulkMetadataDialog({
  open,
  onClose,
  libraryId,
  documentIds,
  onDone,
}: BulkMetadataDialogProps) {
  const [step, setStep] = useState<Step>('form')
  const [fieldKey, setFieldKey] = useState('document_type')
  const [value, setValue] = useState<MetadataValueRequest>({})
  const [vocabulary, setVocabulary] = useState<DocumentTypeVocabularyEntryResponse[]>([])
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState<BulkMetadataValueResponse | null>(null)

  useEffect(() => {
    if (!open) return
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
  }, [open])

  function handleClose() {
    if (submitting) return
    onClose()
  }

  async function handleAssign() {
    setError(null)
    setSubmitting(true)
    try {
      const response = await bulkSetDocumentMetadata(libraryId, {
        fieldKey,
        value: metadataValueRequestFor(fieldKey, value),
        documentIds,
      })
      setResult(response)
      setStep('result')
      onDone(response)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sammelzuweisung fehlgeschlagen')
      setStep('form')
    } finally {
      setSubmitting(false)
    }
  }

  const complete = isMetadataValueComplete(fieldKey, value)

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle>Feld für {documentCountLabel(documentIds.length)} setzen</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          {step === 'form' && (
            <>
              <FormControl fullWidth>
                <InputLabel id="bulk-metadata-field-label">Feld</InputLabel>
                <Select
                  labelId="bulk-metadata-field-label"
                  label="Feld"
                  value={fieldKey}
                  onChange={(e) => {
                    setFieldKey(String(e.target.value))
                    setValue({})
                  }}
                >
                  {CORE_METADATA_FIELDS.map((field) => (
                    <MenuItem key={field.key} value={field.key}>
                      {field.label}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
              <MetadataValueForm
                fieldKey={fieldKey}
                value={value}
                onChange={setValue}
                vocabulary={vocabulary}
              />
              <Typography variant="caption" color="text.secondary">
                Der Wert wird für jedes ausgewählte Dokument als „manuell" gesetzt und ersetzt dort
                den bisherigen Wert des Feldes.
              </Typography>
            </>
          )}
          {step === 'confirm' && (
            <Typography>
              {coreMetadataFieldLabel(fieldKey)} = „
              {describeMetadataValue(fieldKey, value, vocabulary)}" für{' '}
              {documentCountLabel(documentIds.length)} setzen?
            </Typography>
          )}
          {step === 'result' && result && (
            <Stack spacing={1}>
              <Alert severity="success">
                {result.updatedCount} {result.updatedCount === 1 ? 'Dokument' : 'Dokumente'}{' '}
                aktualisiert, {result.unchangedCount} unverändert.
              </Alert>
              {result.rejectedDocumentIds.length > 0 && (
                <Alert severity="warning">
                  {result.rejectedDocumentIds.length}{' '}
                  {result.rejectedDocumentIds.length === 1 ? 'Dokument wurde' : 'Dokumente wurden'}{' '}
                  abgewiesen, weil sie nicht (mehr) zu dieser Bibliothek gehören.
                </Alert>
              )}
            </Stack>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        {step === 'form' && (
          <>
            <Button onClick={handleClose}>Abbrechen</Button>
            <Button variant="contained" onClick={() => setStep('confirm')} disabled={!complete}>
              Weiter
            </Button>
          </>
        )}
        {step === 'confirm' && (
          <>
            <Button onClick={() => setStep('form')} disabled={submitting}>
              Zurück
            </Button>
            <Button variant="contained" onClick={() => void handleAssign()} disabled={submitting}>
              Zuweisen
            </Button>
          </>
        )}
        {step === 'result' && <Button onClick={onClose}>Schließen</Button>}
      </DialogActions>
    </Dialog>
  )
}
