/** The five generic connection fields - what the Pfad- und URL-Formulare edit (#1940). */
export interface GenericSourceValues {
  sourcePath: string
  sourceUrl: string
  sourceProxy: string
  sourceCredentials: string
  sourceInsecureSsl: boolean
}

export const EMPTY_GENERIC_SOURCE_VALUES: GenericSourceValues = {
  sourcePath: '',
  sourceUrl: '',
  sourceProxy: '',
  sourceCredentials: '',
  sourceInsecureSsl: false,
}

/**
 * The source configuration fields shared by LibraryRequest and LibraryUpdateRequest: the five
 * generic ones plus the connector's own settings object (ADR-0038), which stays undefined for a
 * connector without settings.
 */
export interface LibrarySourceConfigPayload {
  sourcePath?: string
  sourceUrl?: string
  sourceProxy?: string
  sourceCredentials?: string
  sourceInsecureSsl: boolean
  sourceSettings?: Record<string, unknown>
}

/** The generic values of a stored library; credentials stay blank, they are never returned. */
export function storedGenericSourceValues(library: {
  sourcePath?: string | null
  sourceUrl?: string | null
  sourceProxy?: string | null
  sourceInsecureSsl?: boolean | null
}): GenericSourceValues {
  return {
    sourcePath: library.sourcePath ?? '',
    sourceUrl: library.sourceUrl ?? '',
    sourceProxy: library.sourceProxy ?? '',
    sourceCredentials: '',
    sourceInsecureSsl: Boolean(library.sourceInsecureSsl),
  }
}

/** The two generic connection shapes: a server path, or an http(s) address with its options. */
export type GenericSourceKind = 'path' | 'url'

/**
 * The fast, obvious-typo rejection before any network call; the connector validates again on the
 * server. Returns a German message on the first violation, or null.
 */
export function validateGenericSource(
  kind: GenericSourceKind,
  values: Pick<GenericSourceValues, 'sourcePath' | 'sourceUrl'>,
): string | null {
  if (kind === 'path') {
    const trimmedPath = values.sourcePath.trim()
    if (!trimmedPath) return 'Verzeichnispfad ist erforderlich'
    if (!trimmedPath.startsWith('/')) {
      return 'Verzeichnispfad muss ein absoluter Pfad sein, z. B. /data/dokumente'
    }
    return null
  }
  const trimmedUrl = values.sourceUrl.trim()
  if (!trimmedUrl) return 'Adresse (URL) ist erforderlich'
  if (!/^https?:\/\//i.test(trimmedUrl)) {
    return 'Adresse (URL) muss mit http:// oder https:// beginnen'
  }
  return null
}

/**
 * The request fields of a generic source: only those of its shape are populated, so the
 * connector's own validation stays the single source of truth for what is allowed.
 */
export function genericSourcePayload(
  kind: GenericSourceKind,
  values: GenericSourceValues,
): LibrarySourceConfigPayload {
  if (kind === 'path') {
    return { sourcePath: values.sourcePath.trim(), sourceInsecureSsl: false }
  }
  return {
    sourceUrl: values.sourceUrl.trim(),
    sourceProxy: values.sourceProxy.trim() || undefined,
    sourceCredentials: values.sourceCredentials.trim() || undefined,
    sourceInsecureSsl: values.sourceInsecureSsl,
  }
}

/**
 * Whether `previousUrl` and `nextUrl` name the same origin (scheme, host and port) - the frontend
 * counterpart of the backend's origin binding of stored credentials, used only to phrase an
 * accurate hint; the backend re-derives it from the persisted value.
 */
export function sameLibrarySourceOrigin(
  previousUrl: string | null | undefined,
  nextUrl: string,
): boolean {
  const trimmedNext = nextUrl.trim()
  if (!previousUrl || !trimmedNext) {
    return false
  }
  try {
    return new URL(previousUrl).origin === new URL(trimmedNext).origin
  } catch {
    return false
  }
}
