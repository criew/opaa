import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import DeleteIcon from '@mui/icons-material/Delete'
import EditIcon from '@mui/icons-material/Edit'
import type { DocumentMetadataFieldResponse } from '../../types/api'
import { deleteDocumentMetadataValue, getDocumentMetadata } from '../../services/api'
import { metadataOriginLabel } from '../../utils/labels'
import EditMetadataValueDialog from './EditMetadataValueDialog'

interface DocumentMetadataPanelProps {
  libraryId: string
  documentId: string
  fileName: string
  canEdit: boolean
  // Bumped by the parent after a bulk assignment so an open panel reloads its values.
  refreshToken?: number
  // Called after this panel changed a value, so the Pflege-Anker (#1069) can recount.
  onValueChanged?: () => void
}

function formatTimestamp(value: string | null | undefined): string {
  if (!value) return ''
  return new Date(value).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
}

/** The tooltip explaining a value's provenance - actor and time for a manual value. */
function originTooltip(field: DocumentMetadataFieldResponse): string {
  switch (field.origin) {
    case 'MANUAL':
      return `${field.state === 'NOT_DETERMINABLE' ? 'Von Hand als „kein Wert ermittelbar“ gekennzeichnet' : 'Von Hand gesetzt'}${field.actorDisplayName ? ` von ${field.actorDisplayName}` : ''}${
        field.updatedAt ? ` am ${formatTimestamp(field.updatedAt)}` : ''
      }`
    case 'DERIVED':
      return `Von einem Modell abgeleitet${
        field.confidence != null ? ` (Konfidenz ${Math.round(field.confidence * 100)} %)` : ''
      }${field.modelId ? `, Modell ${field.modelId}` : ''}`
    case 'DETERMINISTIC':
      return `Aus der Datei ermittelt${
        field.extractionVersion != null ? ` (Extraktionsversion ${field.extractionVersion})` : ''
      }`
    default:
      return ''
  }
}

// #1068: the core fields of one document with the provenance of every value. An empty field is
// shown as such, a field marked "kein Wert ermittelbar" (#1069) as its own state; a DERIVED value
// is always marked "abgeleitet". Editing and deleting are only
// offered to a person who may edit the library's documents.
export default function DocumentMetadataPanel({
  libraryId,
  documentId,
  fileName,
  canEdit,
  refreshToken = 0,
  onValueChanged,
}: DocumentMetadataPanelProps) {
  const [fields, setFields] = useState<DocumentMetadataFieldResponse[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<DocumentMetadataFieldResponse | null>(null)

  useEffect(() => {
    let cancelled = false
    getDocumentMetadata(libraryId, documentId)
      .then((response) => {
        if (cancelled) return
        setFields(response.fields)
        setError(null)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setError(err instanceof Error ? err.message : 'Metadaten konnten nicht geladen werden')
      })
    return () => {
      cancelled = true
    }
  }, [libraryId, documentId, refreshToken])

  function replaceField(saved: DocumentMetadataFieldResponse) {
    setFields((previous) =>
      (previous ?? []).map((field) => (field.fieldKey === saved.fieldKey ? saved : field)),
    )
    onValueChanged?.()
  }

  async function handleDelete(field: DocumentMetadataFieldResponse) {
    if (
      !window.confirm(
        `${field.label} von "${fileName}" löschen? Das Feld ist danach leer; die nächste automatische Extraktion darf es wieder befüllen.`,
      )
    ) {
      return
    }
    try {
      await deleteDocumentMetadataValue(libraryId, documentId, field.fieldKey)
      replaceField({ fieldKey: field.fieldKey, label: field.label, state: 'EMPTY' })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Wert konnte nicht gelöscht werden')
    }
  }

  return (
    <Box
      role="region"
      aria-label={`Metadaten von ${fileName}`}
      sx={{ ml: 4, mr: 1, mb: 1, px: 1.5, py: 1, borderLeft: '2px solid', borderColor: 'divider' }}
    >
      {error && (
        <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {fields === null && !error ? (
        <Typography variant="body2" color="text.secondary">
          Metadaten werden geladen …
        </Typography>
      ) : (
        <Stack spacing={0.5}>
          {(fields ?? []).map((field) => (
            <Stack
              key={field.fieldKey}
              direction="row"
              spacing={1}
              sx={{ alignItems: 'center', minHeight: 32 }}
            >
              <Typography variant="body2" sx={{ minWidth: 110, color: 'text.secondary' }}>
                {field.label}
              </Typography>
              <Typography variant="body2" sx={{ flexGrow: 1, wordBreak: 'break-word' }}>
                {field.state === 'NOT_DETERMINABLE'
                  ? '– (kein Wert ermittelbar)'
                  : field.value != null
                    ? (field.displayValue ?? field.value)
                    : '– (leer)'}
              </Typography>
              {field.origin && (
                <Tooltip title={originTooltip(field)} describeChild>
                  <Chip
                    label={metadataOriginLabel(field.origin)}
                    size="small"
                    variant="outlined"
                    color={field.origin === 'DERIVED' ? 'warning' : 'default'}
                  />
                </Tooltip>
              )}
              {canEdit && (
                <IconButton
                  size="small"
                  aria-label={`${field.label} von ${fileName} bearbeiten`}
                  onClick={() => setEditing(field)}
                >
                  <EditIcon fontSize="small" />
                </IconButton>
              )}
              {canEdit && field.state !== 'EMPTY' && (
                <IconButton
                  size="small"
                  aria-label={`${field.label} von ${fileName} löschen`}
                  onClick={() => void handleDelete(field)}
                >
                  <DeleteIcon fontSize="small" />
                </IconButton>
              )}
            </Stack>
          ))}
        </Stack>
      )}
      {editing && (
        <EditMetadataValueDialog
          key={editing.fieldKey}
          open
          onClose={() => setEditing(null)}
          libraryId={libraryId}
          documentId={documentId}
          fileName={fileName}
          field={editing}
          onSaved={replaceField}
        />
      )}
    </Box>
  )
}
