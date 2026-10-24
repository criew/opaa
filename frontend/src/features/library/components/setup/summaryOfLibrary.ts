import type { SourceConnection } from '../sources/sourceConnection'
import type { SourceConfiguration, SourceSummary, StoredLibrarySource } from '../sources/types'

/** The summary of a stored library, read through its registration's form values. */
export function summaryOfLibrary(
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  configuration: SourceConfiguration<any> | null | undefined,
  library: StoredLibrarySource,
  connection?: SourceConnection,
): SourceSummary | null {
  if (!configuration?.summary) return null
  return configuration.summary(configuration.fromLibrary(library), {
    mode: 'edit',
    sourceType: library.sourceType,
    idPrefix: 'summary',
    credentialsStored: Boolean(library.sourceCredentialsSet),
    originalSourceUrl: library.sourceUrl,
    connection,
  })
}
