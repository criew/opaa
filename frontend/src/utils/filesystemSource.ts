import {
  EMPTY_GENERIC_SOURCE_VALUES,
  genericSourcePayload,
  storedGenericSourceValues,
  validateGenericSource,
  type GenericSourceValues,
  type LibrarySourceConfigPayload,
} from './librarySourceConfig'

/** The form values of a FILESYSTEM library: the path plus its exclusion patterns, one per line. */
export interface FilesystemSourceValues extends GenericSourceValues {
  excludePatterns: string
}

export const EMPTY_FILESYSTEM_VALUES: FilesystemSourceValues = {
  ...EMPTY_GENERIC_SOURCE_VALUES,
  excludePatterns: '',
}

/** Mirrors FilesystemSourceSettings.MAX_PATTERNS / MAX_PATTERN_LENGTH. */
const MAX_PATTERNS = 50
const MAX_PATTERN_LENGTH = 255

/** One pattern per line, blank lines dropped, whitespace trimmed. */
export function filesystemPatternsOf(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line !== '')
}

/** The exclusion patterns a stored library carries. */
export function storedFilesystemPatterns(
  sourceSettings?: Record<string, unknown> | null,
): string[] {
  const raw = sourceSettings?.excludePatterns
  return Array.isArray(raw) ? raw.filter((item): item is string => typeof item === 'string') : []
}

export function storedFilesystemValues(library: {
  sourcePath?: string | null
  sourceSettings?: Record<string, unknown> | null
}): FilesystemSourceValues {
  return {
    ...storedGenericSourceValues(library),
    excludePatterns: storedFilesystemPatterns(library.sourceSettings).join('\n'),
  }
}

/** The connector settings sent with every save, so clearing the patterns is a change too. */
export function filesystemSettingsOf(values: FilesystemSourceValues): Record<string, unknown> {
  return { excludePatterns: filesystemPatternsOf(values.excludePatterns) }
}

/**
 * The fast check before sending; syntax is the server's to judge, it answers an invalid glob with a
 * German message of its own.
 */
export function validateFilesystemValues(values: FilesystemSourceValues): string | null {
  const pathError = validateGenericSource('path', values)
  if (pathError) return pathError
  const patterns = filesystemPatternsOf(values.excludePatterns)
  if (patterns.length > MAX_PATTERNS) {
    return `Höchstens ${MAX_PATTERNS} Ausschlussmuster je Bibliothek`
  }
  if (patterns.some((pattern) => pattern.length > MAX_PATTERN_LENGTH)) {
    return `Ausschlussmuster: ein Muster darf höchstens ${MAX_PATTERN_LENGTH} Zeichen lang sein`
  }
  if (patterns.some((pattern) => pattern.startsWith('/'))) {
    return 'Ausschlussmuster gelten relativ zum Verzeichnispfad und beginnen nicht mit „/“, z. B. Archiv/**'
  }
  if (new Set(patterns).size !== patterns.length) {
    return 'Ausschlussmuster: ein Muster ist mehrfach angegeben'
  }
  return null
}

export function filesystemPayload(values: FilesystemSourceValues): LibrarySourceConfigPayload {
  return { ...genericSourcePayload('path', values), sourceSettings: filesystemSettingsOf(values) }
}
