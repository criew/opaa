import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { LibraryMetadataMaintenanceResponse } from '../../types/api'
import { getLibraryMetadataMaintenance } from '../../services/api'

interface MetadataMaintenanceAnchorProps {
  libraryId: string
  // The core field whose open documents are currently listed, or null for the unfiltered list.
  activeFieldKey: string | null
  onShowMissing: (fieldKey: string) => void
  onClearFilter: () => void
  // Bumped by the parent after any metadata change, so the counts are recomputed.
  refreshToken?: number
}

function percent(share: number): string {
  return `${(share * 100).toLocaleString('de-DE', { maximumFractionDigits: 1 })} %`
}

// #1069 (metadata-schema.md, "Der Pflege-Anker"): per core field, how many of the library's
// indexed documents still have no value - the absolute number and its share side by side, because
// neither alone says whether the field is broken or merely incomplete. The button opens exactly
// those documents in the list below, where they can be corrected one by one or in one go.
export default function MetadataMaintenanceAnchor({
  libraryId,
  activeFieldKey,
  onShowMissing,
  onClearFilter,
  refreshToken = 0,
}: MetadataMaintenanceAnchorProps) {
  const [maintenance, setMaintenance] = useState<LibraryMetadataMaintenanceResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    getLibraryMetadataMaintenance(libraryId)
      .then((response) => {
        if (cancelled) return
        setMaintenance(response)
        setError(null)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setError(
          err instanceof Error ? err.message : 'Metadaten-Pflegestand konnte nicht geladen werden',
        )
      })
    return () => {
      cancelled = true
    }
  }, [libraryId, refreshToken])

  return (
    <Box
      component="section"
      aria-label="Metadaten-Pflege"
      sx={{ mb: 3, p: 2, border: '1px solid', borderColor: 'divider', borderRadius: 1 }}
    >
      <Typography variant="subtitle1" component="h3" sx={{ mb: 0.5 }}>
        Metadaten-Pflege
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        Dokumente ohne Wert je Feld, bezogen auf {maintenance?.totalDocuments ?? 0} indizierte
        Dokumente dieser Bibliothek. Felder, die von Hand als „kein Wert ermittelbar" gekennzeichnet
        sind, zählen nicht mit.
      </Typography>
      {error && (
        <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      <Stack spacing={1}>
        {(maintenance?.fields ?? []).map((field) => (
          <Stack
            key={field.fieldKey}
            direction="row"
            spacing={1.5}
            sx={{ alignItems: 'center', flexWrap: 'wrap' }}
          >
            <Typography variant="body2" sx={{ minWidth: 120, color: 'text.secondary' }}>
              {field.label}
            </Typography>
            <Typography variant="body2" sx={{ minWidth: 220 }}>
              {field.documentsWithoutValue === 0
                ? 'vollständig gepflegt'
                : `${field.documentsWithoutValue} ${
                    field.documentsWithoutValue === 1 ? 'Dokument' : 'Dokumente'
                  } ohne Wert (${percent(field.missingShare)})`}
            </Typography>
            {field.notDeterminableDocuments > 0 && (
              <Typography variant="caption" color="text.secondary">
                {field.notDeterminableDocuments} × kein Wert ermittelbar
              </Typography>
            )}
            {field.documentsWithoutValue > 0 &&
              (activeFieldKey === field.fieldKey ? (
                <Button size="small" onClick={onClearFilter}>
                  Filter aufheben
                </Button>
              ) : (
                <Button
                  size="small"
                  variant="outlined"
                  aria-label={`Dokumente ohne Wert für ${field.label} anzeigen`}
                  onClick={() => onShowMissing(field.fieldKey)}
                >
                  Anzeigen
                </Button>
              ))}
          </Stack>
        ))}
      </Stack>
    </Box>
  )
}
