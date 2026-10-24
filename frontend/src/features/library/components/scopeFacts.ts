import type { IndexingRunResponse, SourceTypeKey } from '@/shared/api/types'
import { sourceRegistration } from './sources/registry'
import type { StoredLibrarySource } from './sources/types'

/** Every connector has an Umfang; those without a selection read everything their source has. */
const WHOLE_SOURCE: Partial<Record<SourceTypeKey, string>> = {
  FILESYSTEM: 'Ganzes Verzeichnis',
  HTTP_DIRECTORY: 'Ganzes Verzeichnis',
  RSS_FEED: 'Alle Einträge des Feeds',
}

/** The Umfang in its short form, the same words in the status band and the row „Umfang“. */
export function scopeShort(library: StoredLibrarySource): string {
  const hero = sourceRegistration(library.sourceType)?.configuration?.scopeHero
  if (hero) return hero.summary(library)
  const whole = WHOLE_SOURCE[library.sourceType] ?? 'Ganze Quelle'
  const excluded = (library.sourceSettings?.excludePatterns as unknown[] | undefined)?.length ?? 0
  return excluded > 0 ? `${whole} · ${excluded} Ausschlüsse` : whole
}

/**
 * The names of the scope entries the newest run could not read: the reported keys resolved to
 * their stored names, or - for a source that reports only a count - the unreachable paths of its
 * events. Never a raw key where the settings know a name.
 */
export function unreadableNames(
  library: StoredLibrarySource,
  unlistedKeys: string[],
  newestRun: IndexingRunResponse | null,
): string[] {
  const hero = sourceRegistration(library.sourceType)?.configuration?.scopeHero
  if (unlistedKeys.length > 0) {
    return unlistedKeys.map((key) => hero?.scopeName?.(key, library) ?? key)
  }
  const count = newestRun?.unreadableScopeCount ?? 0
  if (!newestRun || count === 0) return []
  const paths = newestRun.events
    .filter((event) => event.category === 'UNREACHABLE' && event.reference)
    .map((event) => event.reference as string)
  return [...new Set(paths)].slice(0, count)
}
