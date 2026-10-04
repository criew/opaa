import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import {
  storedFilesystemPatterns,
  type FilesystemSourceValues,
} from '../../../utils/filesystemSource'
import {
  storedGenericSourceValues,
  type GenericSourceKind,
  type GenericSourceValues,
} from '../../../utils/librarySourceConfig'
import PathSourceForm from '../PathSourceForm'
import SourceConnectionTest from '../SourceConnectionTest'
import UrlSourceForm from '../UrlSourceForm'
import { connectionFields } from './sourceConnection'
import type { SourceFormProps, StoredLibrarySource } from './types'

export function PathForm({ values, onChange, context }: SourceFormProps<FilesystemSourceValues>) {
  return (
    <>
      <PathSourceForm
        mode={context.mode}
        idPrefix={context.idPrefix}
        values={values}
        onChange={onChange}
      />
      <SourceConnectionTest
        sourceType={context.sourceType}
        kind="path"
        values={values}
        probe={connectionFields(context).probe}
        size={context.mode === 'edit' ? 'small' : 'medium'}
      />
    </>
  )
}

export function UrlForm({ values, onChange, context }: SourceFormProps<GenericSourceValues>) {
  const fields = connectionFields(context)
  return (
    <>
      <UrlSourceForm
        mode={context.mode}
        sourceType={context.sourceType}
        idPrefix={context.idPrefix}
        values={values}
        onChange={onChange}
        credentialsStored={context.credentialsStored}
        originalSourceUrl={context.originalSourceUrl}
        connection={fields}
      />
      <SourceConnectionTest
        sourceType={context.sourceType}
        kind="url"
        values={values}
        probe={fields.probe}
        size={context.mode === 'edit' ? 'small' : 'medium'}
      />
    </>
  )
}

export function StoredConnection({
  library,
  libraryId,
  kind,
}: {
  library: StoredLibrarySource
  libraryId: string
  kind: GenericSourceKind
}) {
  return (
    <>
      {kind === 'path' ? (
        <>
          <Typography variant="body2">
            <strong>Verzeichnispfad:</strong> {library.sourcePath ?? '—'}
          </Typography>
          <Typography variant="body2">
            <strong>Ausschlussmuster:</strong>{' '}
            {storedFilesystemPatterns(library.sourceSettings).join(', ') || 'keine'}
          </Typography>
        </>
      ) : (
        <>
          <Typography variant="body2">
            <strong>Adresse (URL):</strong> {library.sourceUrl ?? '—'}
          </Typography>
          <RemoteConnectionLines library={library} />
        </>
      )}
      {/* #1940: the stored configuration can be probed without opening the editor first. */}
      <Box sx={{ pt: 1 }}>
        <SourceConnectionTest
          sourceType={library.sourceType}
          kind={kind}
          values={storedGenericSourceValues(library)}
          probe={{ libraryId }}
          size="small"
        />
      </Box>
    </>
  )
}

/** Proxy, certificate switch and the note on credentials - shared by every remote source. */
export function RemoteConnectionLines({ library }: { library: StoredLibrarySource }) {
  return (
    <>
      <Typography variant="body2">
        <strong>Proxy:</strong> {library.sourceProxy ?? 'nicht konfiguriert'}
      </Typography>
      <Typography variant="body2">
        <strong>Zertifikatsprüfung aussetzen:</strong> {library.sourceInsecureSsl ? 'ja' : 'nein'}
      </Typography>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        Zugangsdaten sind aus Sicherheitsgründen nie Teil einer API-Antwort - diese Ansicht zeigt
        sie deshalb weder ein noch aus.
      </Typography>
    </>
  )
}
