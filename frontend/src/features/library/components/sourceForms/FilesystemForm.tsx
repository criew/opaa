import Box from '@mui/material/Box'
import type { FilesystemSourceValues } from '../../utils/filesystemSource'
import type { SourceFormProps } from '../sources/types'
import { FieldGroup, SourceTextField } from '../sourceKit/fields'

/**
 * The file-system form (Variante E), the small case: one directory on the server and optional
 * exclusion patterns - no sign-in, no selection.
 */
export default function FilesystemForm({
  values,
  onChange,
  context,
}: SourceFormProps<FilesystemSourceValues>) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <FieldGroup title="Verbindung">
        <SourceTextField
          idPrefix={context.idPrefix}
          field="sourcePath"
          label="Verzeichnis"
          value={values.sourcePath}
          onChange={(sourcePath) => onChange({ sourcePath })}
          placeholder="/data/dokumente"
          hint="Pfad auf dem OPAA-Server; alles darunter wird gelesen."
          mono
        />
      </FieldGroup>
      <FieldGroup title="Umfang">
        <SourceTextField
          idPrefix={context.idPrefix}
          field="excludePatterns"
          label="Ausschlussmuster"
          optional
          multiline
          mono
          value={values.excludePatterns}
          onChange={(excludePatterns) => onChange({ excludePatterns })}
          placeholder="Archiv/**"
          hint="Ein Muster je Zeile, relativ zum Verzeichnis."
        />
      </FieldGroup>
    </Box>
  )
}
