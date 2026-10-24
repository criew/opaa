import { filesystemPayload, type FilesystemSourceValues } from '../../utils/filesystemSource'
import type { SourceFormProps } from '../sources/types'
import ConnectionCheck from './ConnectionCheck'
import SetupField from './SetupField'
import SourceSetupLayout from './SourceSetupLayout'
import { testRequestOf, tokenOf } from './probe'

/** Dateisystem: a server path, optional exclusions - the smallest form on the shared frame. */
export default function FilesystemSetup({
  values,
  onChange,
  context,
}: SourceFormProps<FilesystemSourceValues>) {
  return (
    <SourceSetupLayout
      connection={
        <SetupField
          field="sourcePath"
          label="Verzeichnispfad"
          value={values.sourcePath}
          onChange={(sourcePath) => onChange({ sourcePath })}
          placeholder="/data/dokumente"
          hint="Absoluter Pfad auf dem Server."
          mono
        />
      }
      check={
        <ConnectionCheck
          stages={['address', 'access']}
          token={tokenOf(values.sourcePath)}
          request={() =>
            values.sourcePath.trim() ? testRequestOf(context, filesystemPayload(values)) : null
          }
          missingHint="Bitte zuerst den Verzeichnispfad eingeben."
        />
      }
      scope={
        <SetupField
          field="excludePatterns"
          label="Ausschlussmuster (optional)"
          value={values.excludePatterns}
          onChange={(excludePatterns) => onChange({ excludePatterns })}
          placeholder={'Archiv/**\n**/*.tmp'}
          hint="Ein Muster je Zeile; ** steht für beliebig viele Ordnerebenen."
          mono
          multiline
        />
      }
    />
  )
}
