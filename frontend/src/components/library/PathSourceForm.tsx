import Box from '@mui/material/Box'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import FieldLabel from '../wizard/FieldLabel'
import type { GenericSourceValues } from '../../utils/librarySourceConfig'

interface PathSourceFormProps {
  /** `create` adds the step's own heading; `edit` sits inside a dialog that already has one. */
  mode: 'create' | 'edit'
  idPrefix: string
  values: GenericSourceValues
  onChange: (patch: Partial<GenericSourceValues>) => void
}

/**
 * The one Verzeichnispfad-Formular of a FILESYSTEM library (#1940) - used by the Anlage-Assistent
 * and by the Bearbeiten-Weg of the Reiter „Quelle" alike, so the field, its hint and its length
 * limit exist exactly once. Shaped like {@link ConfluenceSourceForm}: controlled values in, a patch
 * out; the caller owns the state and decides what saving means.
 */
export default function PathSourceForm({ mode, idPrefix, values, onChange }: PathSourceFormProps) {
  return (
    <Box>
      {mode === 'create' && (
        <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.75 }}>
          Verbindung zum Dateisystem
        </Typography>
      )}
      <FieldLabel htmlFor={`${idPrefix}-path`}>Verzeichnispfad</FieldLabel>
      <TextField
        id={`${idPrefix}-path`}
        size="small"
        fullWidth
        value={values.sourcePath}
        onChange={(e) => onChange({ sourcePath: e.target.value })}
        placeholder="/data/dokumente"
        helperText="Absoluter Pfad auf dem Server, den OPAA regelmäßig einliest."
        slotProps={{ htmlInput: { maxLength: 2000, sx: { fontFamily: 'monospace' } } }}
      />
    </Box>
  )
}
