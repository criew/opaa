import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { testLibrarySource } from '../../../api/libraryApi'
import { genericSourcePayload } from '../../../utils/librarySourceConfig'
import type { FilesystemSourceValues } from '../../../utils/filesystemSource'
import { connectionFields } from '../../sources/sourceConnection'
import type { SourceFormProps } from '../../sources/types'
import ConnectionStage from '../ConnectionStage'
import { outcomeOf, outcomeOfError, type ConnectionOutcome } from '../connectionOutcome'
import SourceField from '../SourceField'

/**
 * The source form of a server directory - the small case: a path in „Verbindung", the exclusion
 * patterns as the whole selection, no listing.
 */
export default function FilesystemForm({
  values,
  onChange,
  context,
}: SourceFormProps<FilesystemSourceValues>) {
  const id = `${context.idPrefix}-filesystem`
  const connection = connectionFields(context)
  const token = JSON.stringify({ probe: connection.probe, path: values.sourcePath })

  const run = async (): Promise<ConnectionOutcome> => {
    try {
      const response = await testLibrarySource(
        connection.probeRequest({
          sourceType: 'FILESYSTEM',
          ...genericSourcePayload('path', values),
        }),
      )
      return outcomeOf(response, ['address', 'rights'])
    } catch (err) {
      return outcomeOfError(err, ['address', 'rights'])
    }
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <ConnectionStage
        idPrefix={id}
        token={token}
        stages={['address', 'rights']}
        run={run}
        summary={[values.sourcePath.trim()]}
        fields={['sourcePath']}
        autoTest={values.sourcePath.trim() !== ''}
      >
        <SourceField
          id={`${id}-path`}
          field="sourcePath"
          label="Pfad"
          value={values.sourcePath}
          onChange={(next) => onChange({ sourcePath: next })}
          placeholder="/data/dokumente"
          hint="Verzeichnis auf dem OPAA-Server; alle Unterordner werden gelesen."
          mono
        />
      </ConnectionStage>
      <Box>
        <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.5 }}>
          Auswahl
        </Typography>
        <SourceField
          id={`${id}-exclude`}
          field="excludePatterns"
          label="Ausschlussmuster"
          optional
          value={values.excludePatterns}
          onChange={(next) => onChange({ excludePatterns: next })}
          placeholder={'Archiv/**\n**/*.tmp'}
          hint="Ein Muster je Zeile, relativ zum Pfad."
          multiline
          mono
        />
      </Box>
    </Box>
  )
}
