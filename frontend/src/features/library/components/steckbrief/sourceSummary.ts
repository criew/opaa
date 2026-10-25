import type { LibraryResponse } from '@/shared/api/types'
import { scheduleValuesFrom } from '../../utils/librarySchedule'
import { scheduleSummary } from '../setup/scheduleSummary'
import { sourceRegistration } from '../sources/registry'
import { sourceConnectionOfRef, withConnection } from '../sources/sourceConnection'
import type { SourceFormContext } from '../sources/types'

/** The rows of the Reiter „Quelle“ as short lines - the Steckbrief mirrors them, without „Ändern“. */
export interface SourceSummary {
  connection: string | null
  /** Host or path only - the same short form for every type (Steckbrief). */
  address: string | null
  scope: string | null
  schedule: string | null
  nextRun: string | null
  /** Whether the source offers live updates (webhook, events) as a fourth row. */
  live: boolean
}

/** Scope of a source without a choice: the whole directory or feed (pruefliste-detail D9). */
export function fixedScopeOf(sourceType: LibraryResponse['sourceType']): string | null {
  if (sourceType === 'HTTP_DIRECTORY') return 'Ganzes Verzeichnis'
  if (sourceType === 'RSS_FEED') return 'Alle Einträge des Feeds'
  return null
}

/** Next planned run, short: „nächster 25.10., 08:00“. */
export function nextRunLabel(nextRunAt: string | null | undefined): string | null {
  if (!nextRunAt) return null
  const date = new Date(nextRunAt)
  return `nächster ${date.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' })}, ${date.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })}`
}

export function sourceSummaryOf(library: LibraryResponse, libraryId: string): SourceSummary {
  const configuration = sourceRegistration(library.sourceType)?.configuration ?? null
  const live = library.sourceType === 'CONFLUENCE' || library.sourceType === 'S3'
  if (!configuration)
    return { connection: null, address: null, scope: null, schedule: null, nextRun: null, live }
  const profile = library.connectionProfile ?? null
  const connection = (profile && sourceConnectionOfRef(profile)) ?? undefined
  const context: SourceFormContext = {
    mode: 'edit',
    sourceType: library.sourceType,
    idPrefix: 'steckbrief',
    libraryId,
    credentialsStored: Boolean(library.sourceCredentialsSet),
    originalSourceUrl: library.sourceUrl,
    connection,
    privateLibrary: library.privateLibrary,
  }
  const values = withConnection(configuration.fromLibrary(library), connection)
  const section = (id: 'connection' | 'scope') =>
    configuration.setup?.sections.find((s) => s.id === id)
  const lead = (summary: ReturnType<NonNullable<ReturnType<typeof section>>['summary']>) =>
    summary == null ? null : typeof summary === 'string' ? summary : summary.lead
  const connectionText = lead(section('connection')?.summary(values, context) ?? null)
  const rhythm = configuration.fullSyncRhythm
    ? {
        intervalDays:
          (library.sourceSettings?.fullSyncIntervalDays as number | null | undefined) ?? null,
        defaultDays: library.fullSyncIntervalDefaultDays ?? null,
      }
    : undefined
  const plan = scheduleSummary(scheduleValuesFrom(library.schedule, rhythm), rhythm)
  // a bare „aus“ says nothing out of its row
  const schedule = plan === 'aus' ? 'Zeitplan aus' : plan
  const scope =
    lead(section('scope')?.summary(values, context) ?? null) ?? fixedScopeOf(library.sourceType)
  return {
    address: addressOf(library),
    nextRun: nextRunLabel(library.schedule?.nextRunAt),
    connection:
      [
        profile
          ? /^Zugang\b/.test(profile.name)
            ? profile.name
            : `Zugang „${profile.name}“`
          : null,
        connectionText,
      ]
        .filter(Boolean)
        .join(' · ') || null,
    // a Confluence space is not an OPAA space: the Steckbrief names both, one below the other
    scope:
      library.sourceType === 'CONFLUENCE' && scope
        ? scope.replace(/\bSpaces?\b/, (word) => `Confluence-${word}`)
        : scope,
    schedule,
    live,
  }
}

/** Host of the source address, the path of a file system, or else the Zugang. */
function addressOf(library: LibraryResponse): string | null {
  if (library.sourceUrl) {
    try {
      const url = new URL(library.sourceUrl)
      return url.port ? `${url.hostname}:${url.port}` : url.hostname
    } catch {
      return library.sourceUrl
    }
  }
  if (library.sourcePath) return library.sourcePath
  return library.connectionProfile?.name ?? null
}

function settingsList<T>(library: LibraryResponse, key: string): T[] {
  return (((library.sourceSettings ?? {}) as Record<string, unknown>)[key] as T[] | undefined) ?? []
}

/**
 * The names of the scope entries the last run could not read, resolved from the library's own
 * selection - never the raw keys (`drive:…`) the backend reports (D8).
 */
export function unreadableScopeNames(keys: string[], library: LibraryResponse): string[] {
  return keys.map((key) => {
    switch (library.sourceType) {
      case 'CONFLUENCE':
        return (
          settingsList<{ key: string; name?: string }>(library, 'spaces').find((s) => s.key === key)
            ?.name ?? key
        )
      case 'SHAREPOINT': {
        const id = key.replace(/^drive:/, '')
        return (
          settingsList<{ driveId: string; name?: string }>(library, 'libraries').find(
            (entry) => entry.driveId === id,
          )?.name ?? 'Dokumentbibliothek ohne Namen'
        )
      }
      case 'GOOGLE_DRIVE': {
        const [kind, id] = key.split(':')
        return (
          settingsList<{ drive?: string; folder?: string; name?: string }>(library, 'scopes').find(
            (scope) => (kind === 'drive' ? scope.drive === id : scope.folder === id),
          )?.name ?? 'Bereich ohne Namen'
        )
      }
      case 'S3':
        return key.replace(/\/$/, '')
      default:
        return key
    }
  })
}
