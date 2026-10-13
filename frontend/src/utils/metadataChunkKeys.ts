/**
 * The chunk metadata keys that carry schema metadata - the filterable core fields, the library
 * fields and the format fields with their precision and presence marks. Mirrors the backend's
 * `CoreMetadataChunkKeys`, `LibraryMetadataFieldKeys` and `FormatMetadataField`; every other
 * key (document, library, permission, source, Fundort) is no metadata in that sense.
 */
const CORE_FIELD_KEYS = new Set(['doc_type', 'doc_date', 'doc_date_precision'])
const FIELD_KEY_PREFIXES = ['lf_', 'lfp_', 'lfs_', 'ff_', 'ffs_']

export function isSchemaMetadataChunkKey(key: string): boolean {
  return CORE_FIELD_KEYS.has(key) || FIELD_KEY_PREFIXES.some((prefix) => key.startsWith(prefix))
}

/**
 * The entries of a chunk's stored metadata a page shows, sorted by key: all of them, or - with
 * metadata switched off for the installation (ADR-0044) - all but the schema metadata keys, which
 * an older chunk may still carry.
 */
export function visibleChunkMetadata(
  metadata: Record<string, unknown>,
  metadataEnabled: boolean,
): Array<[string, unknown]> {
  return Object.entries(metadata)
    .filter(([key]) => metadataEnabled || !isSchemaMetadataChunkKey(key))
    .sort(([a], [b]) => a.localeCompare(b))
}
