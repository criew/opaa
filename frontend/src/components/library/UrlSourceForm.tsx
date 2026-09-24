import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { DocumentSourceType } from '../../types/api'
import { documentSourceTypeLabel } from '../../utils/labels'
import { sameLibrarySourceOrigin, type GenericSourceValues } from '../../utils/librarySourceConfig'
import FieldLabel from '../wizard/FieldLabel'

interface UrlSourceFormProps {
  /** `create` adds the step's own heading and the two-column layout of the wizard. */
  mode: 'create' | 'edit'
  sourceType: DocumentSourceType
  idPrefix: string
  values: GenericSourceValues
  onChange: (patch: Partial<GenericSourceValues>) => void
  /** Edit mode: whether credentials are stored for this library. */
  credentialsStored?: boolean
  /** Edit mode: the address the stored credentials belong to - they do not survive a host change. */
  originalSourceUrl?: string | null
}

/**
 * The one Adress-Formular of a HTTP_DIRECTORY or RSS_FEED library (#1940): address, proxy,
 * credentials and the certificate switch - used by the Anlage-Assistent and by the Bearbeiten-Weg
 * of the Reiter „Quelle" alike. The credentials field is write-only in both modes (ADR-0018); in
 * edit mode its hint says what leaving it blank means, including the case where a changed host
 * discards the stored value (#542).
 */
export default function UrlSourceForm({
  mode,
  sourceType,
  idPrefix,
  values,
  onChange,
  credentialsStored = false,
  originalSourceUrl,
}: UrlSourceFormProps) {
  const isCreate = mode === 'create'
  // Mirrors KnowledgeLibraryService's own carry-forward rule purely to phrase an accurate hint;
  // the backend re-derives it from the persisted value and stays the authoritative check.
  const originChanged =
    credentialsStored &&
    values.sourceUrl.trim() !== '' &&
    !sameLibrarySourceOrigin(originalSourceUrl, values.sourceUrl)

  const credentialsHelperText = isCreate
    ? 'Wird nie in einer API-Antwort ausgegeben.'
    : !credentialsStored
      ? 'Für diese Quelle sind aktuell keine Zugangsdaten hinterlegt. Nur ausfüllen, wenn die Quelle eine Anmeldung verlangt.'
      : originChanged
        ? 'Die Adresse zeigt auf einen anderen Server - die bestehenden Zugangsdaten werden dabei verworfen. Bitte bei Bedarf neu eingeben.'
        : 'Leer lassen, um die bestehenden Zugangsdaten beizubehalten. Wird nie in einer API-Antwort ausgegeben.'

  return (
    <Box>
      {isCreate && (
        <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.75 }}>
          Verbindung zum {documentSourceTypeLabel(sourceType)}
        </Typography>
      )}
      {sourceType === 'RSS_FEED' && (
        <Alert severity="info" sx={{ mb: 1.75 }}>
          OPAA ruft neben dem Feed auch die von ihm verlinkten Detailseiten ab. Welche Adressen das
          sind, bestimmt der Betreiber des Feeds, nicht Sie selbst.
        </Alert>
      )}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: isCreate ? { xs: '1fr', sm: '1fr 1fr' } : '1fr',
          gap: '14px',
        }}
      >
        <Box sx={{ gridColumn: '1 / -1' }}>
          <FieldLabel htmlFor={`${idPrefix}-url`}>Adresse (URL)</FieldLabel>
          <TextField
            id={`${idPrefix}-url`}
            size="small"
            fullWidth
            value={values.sourceUrl}
            onChange={(e) => onChange({ sourceUrl: e.target.value })}
            placeholder="https://files.example.com/dokumente/"
            helperText="http oder https."
            slotProps={{ htmlInput: { maxLength: 2000, sx: { fontFamily: 'monospace' } } }}
          />
        </Box>
        <Box>
          <FieldLabel htmlFor={`${idPrefix}-proxy`}>Proxy (optional)</FieldLabel>
          <TextField
            id={`${idPrefix}-proxy`}
            size="small"
            fullWidth
            value={values.sourceProxy}
            onChange={(e) => onChange({ sourceProxy: e.target.value })}
            placeholder="proxy.example.com:8080"
            autoComplete="off"
            slotProps={{ htmlInput: { maxLength: 255 } }}
          />
        </Box>
        <Box>
          <FieldLabel htmlFor={`${idPrefix}-credentials`}>
            {isCreate ? 'Anmeldedaten (optional)' : 'Neue Zugangsdaten'}
          </FieldLabel>
          <TextField
            id={`${idPrefix}-credentials`}
            size="small"
            type="password"
            fullWidth
            value={values.sourceCredentials}
            onChange={(e) => onChange({ sourceCredentials: e.target.value })}
            placeholder="benutzer:passwort"
            helperText={credentialsHelperText}
            autoComplete="new-password"
            slotProps={{ htmlInput: { maxLength: 500 } }}
          />
        </Box>
        <FormControlLabel
          sx={{ gridColumn: '1 / -1' }}
          control={
            <Switch
              checked={values.sourceInsecureSsl}
              onChange={(e) => onChange({ sourceInsecureSsl: e.target.checked })}
            />
          }
          label="Zertifikatsprüfung aussetzen"
        />
      </Box>
    </Box>
  )
}
