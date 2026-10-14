import { issueAt, messageOf, type SourceIssue } from './sourceIssue'

/** The generic connection fields - what the Pfad- und URL-Formulare edit (#1940). */
export interface GenericSourceValues {
  sourcePath: string
  sourceUrl: string
  sourceCredentials: string
}

export const EMPTY_GENERIC_SOURCE_VALUES: GenericSourceValues = {
  sourcePath: '',
  sourceUrl: '',
  sourceCredentials: '',
}

/**
 * The source configuration fields shared by LibraryRequest and LibraryUpdateRequest: the
 * generic ones plus the connector's own settings object (ADR-0038), which stays undefined for a
 * connector without settings.
 */
export interface LibrarySourceConfigPayload {
  sourcePath?: string
  sourceUrl?: string
  sourceCredentials?: string
  sourceSettings?: Record<string, unknown>
}

/** The generic values of a stored library; credentials stay blank, they are never returned. */
export function storedGenericSourceValues(library: {
  sourcePath?: string | null
  sourceUrl?: string | null
}): GenericSourceValues {
  return {
    sourcePath: library.sourcePath ?? '',
    sourceUrl: library.sourceUrl ?? '',
    sourceCredentials: '',
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
  return messageOf(genericSourceIssue(kind, values))
}

/** {@link validateGenericSource} with the field the message is about. */
export function genericSourceIssue(
  kind: GenericSourceKind,
  values: Pick<GenericSourceValues, 'sourcePath' | 'sourceUrl'>,
): SourceIssue | null {
  if (kind === 'path') {
    const trimmedPath = values.sourcePath.trim()
    if (!trimmedPath) return issueAt('sourcePath', 'Verzeichnispfad ist erforderlich')
    if (!trimmedPath.startsWith('/')) {
      return issueAt(
        'sourcePath',
        'Verzeichnispfad muss ein absoluter Pfad sein, z. B. /data/dokumente',
      )
    }
    return null
  }
  const trimmedUrl = values.sourceUrl.trim()
  if (!trimmedUrl) return issueAt('sourceUrl', 'Adresse (URL) ist erforderlich')
  if (!/^https?:\/\//i.test(trimmedUrl)) {
    return issueAt('sourceUrl', 'Adresse (URL) muss mit http:// oder https:// beginnen')
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
    return { sourcePath: values.sourcePath.trim() }
  }
  return {
    sourceUrl: values.sourceUrl.trim(),
    sourceCredentials: values.sourceCredentials.trim() || undefined,
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
