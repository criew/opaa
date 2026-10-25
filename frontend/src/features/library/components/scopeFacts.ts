import type { IndexingRunResponse, SourceTypeKey } from '@/shared/api/types'
import { sourceRegistration } from './sources/registry'
import type { StoredLibrarySource } from './sources/types'

/** Sources without a selection read everything their source has; their Umfang names that. */
const WHOLE_SOURCE: Partial<Record<SourceTypeKey, string>> = {
  HTTP_DIRECTORY: 'Ganzes Verzeichnis',
  RSS_FEED: 'Alle Einträge des Feeds',
}

/**
 * The Umfang in its short form: the lead of the row „Umfang“ in the Reiter „Quelle“, from the
 * same function, so band and row always use the same words and the same count.
 */
export function scopeShort(library: StoredLibrarySource): string {
  const configuration = sourceRegistration(library.sourceType)?.configuration
  const section = configuration?.setup?.sections.find((entry) => entry.id === 'scope')
  if (configuration && section) {
    const summary = section.summary(configuration.fromLibrary(library), {
      mode: 'edit',
      sourceType: library.sourceType,
      idPrefix: 'band',
      credentialsStored: Boolean(library.sourceCredentialsSet),
    })
    if (typeof summary === 'string') return summary
    if (summary) return summary.lead
  }
  return WHOLE_SOURCE[library.sourceType] ?? 'Ganze Quelle'
}

/** Whether the Umfang can be chosen at all; a source without a choice has no row to change. */
export function hasScopeChoice(library: StoredLibrarySource): boolean {
  return !WHOLE_SOURCE[library.sourceType]
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
