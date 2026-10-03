import Box from '@mui/material/Box'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import FieldLabel from '../wizard/FieldLabel'
import type { FilesystemSourceValues } from '../../utils/filesystemSource'

interface PathSourceFormProps {
  /** `create` adds the step's own heading; `edit` sits inside a dialog that already has one. */
  mode: 'create' | 'edit'
  idPrefix: string
  values: FilesystemSourceValues
  onChange: (patch: Partial<FilesystemSourceValues>) => void
}

/**
 * The one Verzeichnispfad-Formular of a FILESYSTEM library (#1940) - used by the Anlage-Assistent
 * and by the Bearbeiten-Weg of the Reiter „Quelle" alike, so the fields, their hints and limits
 * exist exactly once. Shaped like {@link ConfluenceSourceForm}: controlled values in, a patch
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
      <Box sx={{ mt: 2 }}>
        <FieldLabel htmlFor={`${idPrefix}-exclude`}>Ausschlussmuster (optional)</FieldLabel>
        <TextField
          id={`${idPrefix}-exclude`}
          size="small"
          fullWidth
          multiline
          minRows={2}
          value={values.excludePatterns}
          onChange={(e) => onChange({ excludePatterns: e.target.value })}
          placeholder={'Archiv/**\n**/*.tmp'}
          helperText="Ein Muster pro Zeile, relativ zum Verzeichnispfad: * steht für beliebige Zeichen innerhalb eines Ordners, ** für beliebig viele Ordnerebenen. Versteckte Einträge (Name beginnt mit einem Punkt) und Systemordner wie $RECYCLE.BIN werden immer übersprungen. Bereits aufgenommene Dokumente, die ein Muster ausschließt, entfernt der nächste Lauf."
          slotProps={{ htmlInput: { sx: { fontFamily: 'monospace' } } }}
        />
      </Box>
    </Box>
  )
}
