import type { IndexingRunListResponse, LibraryResponse } from '@/shared/api/types'
import {
  LONG_COMPOUND,
  currentMockRole,
  currentMockScenario,
  generate,
  scenarioName,
  type MockScenario,
} from '@/shared/mocks/mockScenario'
import { mockIndexingRuns } from './indexingFixtures'
import { scenarioSpaces, siteName } from './sourceScenarioMock'
import {
  mockLibraries,
  mockLibraryDetails,
  mockLibraryDocuments,
  mockLibraryFolders,
} from './libraryFixtures'
import { mockLibraryGrants } from '@/features/asset/mocks/assetFixtures'
import {
  CONFLUENCE_PAGES,
  FIXTURE_SCHEDULE,
  berlin,
  entryBase,
  runningStatus,
  unlistedKeysOf,
  detailLabLibraries,
  scenarioDocuments,
  scenarioFolders,
  scenarioGrants,
} from './detailScenarioFixtures'
import { SOURCE_LIBRARY_IDS, SOURCE_LIBRARY_LAST_INDEXED } from './sourceLibraryFixtures'

/**
 * The connected-library fixtures under the fixture scenarios: `scale` widens every selection to
 * dozens or hundreds of entries with long names, every other scenario keeps one scope, `empty` leaves the
 * libraries without documents and runs, `failed` and `partial` change their run history.
 */

type Overlay = (library: LibraryResponse) => Partial<LibraryResponse>

const LONG_LIBRARY_SUFFIX = `– ${LONG_COMPOUND}n, Bauleitplanung und Übergabeordner der Ämter (2009–2026)`

const LEVELS = ['Ordner', 'Vorgang', 'Akte', 'Jahrgang', 'Teil', 'Band', 'Abschnitt', 'Anlage']

/** A path of the generated folder tree, `depth` levels deep; skips the names with a comma. */
function deepPath(i: number, depth: number) {
  const n = i + Math.floor((i - 1) / 2)
  return `/${generate(depth, (level) => scenarioName(LEVELS[level - 1], n)).join('/')}`
}

const SCALE: Record<string, Overlay> = {
  [SOURCE_LIBRARY_IDS.FILESYSTEM]: () => ({
    sourcePath: `/srv/opaa/${LONG_COMPOUND.toLowerCase()}/fachbereich-soziales-jugend-und-familie/eingliederungshilfe`,
    sourceSettings: {
      excludePatterns: generate(
        40,
        (i) => `**/${scenarioName('Entwurf', i).replace(/ /g, '_')}/**`,
      ),
    },
  }),
  [SOURCE_LIBRARY_IDS.CONFLUENCE]: () => ({
    sourceSettings: {
      edition: 'CLOUD',
      spaces: scenarioSpaces(120).map((space) => ({ key: space.key, name: space.name ?? null })),
      fullSyncIntervalDays: 7,
    },
  }),
  [SOURCE_LIBRARY_IDS.SHAREPOINT]: () => ({
    sourceSettings: {
      libraries: generate(25, (i) => {
        const site = ((i - 1) % 17) + 1
        const drive = `gen-drive-${site}-${i}`
        const library = i === 1 ? 'Dokumente' : scenarioName('Dokumentbibliothek', i)
        return {
          driveId: drive,
          name: `${library} (${siteName(site)})`,
          siteId: `gen-site-${site}`,
          siteName: siteName(site),
          folders: generate(i % 5 === 0 ? 12 : 2, (f) => ({
            id: `${drive}_F${f}-${f}-${f}`,
            name: ['Ordner', 'Vorgang', 'Akte'].map((base) => scenarioName(base, f)).join(' / '),
          })),
        }
      }),
      fullSyncIntervalDays: 1,
    },
  }),
  [SOURCE_LIBRARY_IDS.NEXTCLOUD]: () => ({
    sourceSettings: { folders: generate(50, (i) => deepPath(i, (i % 8) + 1)) },
  }),
  [SOURCE_LIBRARY_IDS.SMB]: () => ({
    sourceSettings: { folders: generate(50, (i) => deepPath(i, (i % 8) + 1)) },
  }),
  [SOURCE_LIBRARY_IDS.DROPBOX]: () => ({
    sourceSettings: {
      folders: generate(50, (i) => deepPath(i, (i % 8) + 1)),
      teamSpace: true,
      fullSyncIntervalDays: 7,
    },
  }),
  [SOURCE_LIBRARY_IDS.GOOGLE_DRIVE]: () => ({
    sourceSettings: {
      scopes: generate(50, (i) =>
        i % 4 === 1
          ? { drive: `0AGen${i}`, name: scenarioName('Geteilte Ablage', i) }
          : { folder: `1Gen${i}`, name: scenarioName('Freigegebener Ordner', i) },
      ),
      subject: 'dienstkonto-indexierung@rheinfurt-verwaltung.iam.gserviceaccount.com',
      fullSyncIntervalDays: 7,
    },
  }),
  [SOURCE_LIBRARY_IDS.S3]: (library) => ({
    sourceSettings: {
      ...(library.sourceSettings ?? {}),
      scopes: generate(50, (i) => ({
        bucket: `bucket-${String(i).padStart(4, '0')}${i % 3 === 0 ? '-archiv-eingliederungshilfe-teilhabe-2009' : ''}`,
        prefix:
          i % 2 === 0
            ? `${scenarioName('vorgang', i).replace(/ /g, '-').toLowerCase()}/2025/`
            : null,
      })),
    },
  }),
}

/**
 * The scope every scenario but `scale` keeps, so a library does not change its Umfang from one
 * scenario to the next: two entries wherever the source selects (one can then fail in `partial`).
 */
const BASE: Record<string, Overlay> = {
  [SOURCE_LIBRARY_IDS.SHAREPOINT]: (library) => ({
    sourceSettings: {
      ...library.sourceSettings,
      libraries: [
        {
          driveId: 'gen-drive-1-1',
          name: 'Dokumente (Rathaus)',
          siteId: 'gen-site-1',
          siteName: 'Rathaus',
        },
        {
          driveId: 'gen-drive-1-2',
          name: 'Ratsbeschlüsse (Rathaus)',
          siteId: 'gen-site-1',
          siteName: 'Rathaus',
        },
      ],
    },
  }),
  [SOURCE_LIBRARY_IDS.GOOGLE_DRIVE]: (library) => ({
    sourceSettings: {
      ...library.sourceSettings,
      scopes: [
        { drive: '0AMockDrive', name: 'Ablage Projektteam' },
        { folder: '1MockFolder', name: 'Freigegebene Akten' },
      ],
      // the full sync its runs report needs a rhythm in the schedule
      fullSyncIntervalDays: 7,
    },
  }),
  [SOURCE_LIBRARY_IDS.DROPBOX]: (library) => ({
    sourceSettings: { ...library.sourceSettings, fullSyncIntervalDays: 7 },
  }),
}

function overlayFor(scenario: MockScenario, id: string): Overlay | null {
  if (scenario === 'default') return null
  const base = (library: LibraryResponse) => ({
    ...library,
    ...BASE[id]?.(library),
    ...(library.schedule ? { schedule: { ...library.schedule, ...FIXTURE_SCHEDULE } } : {}),
  })
  switch (scenario) {
    case 'scale':
      return (library) => {
        const based = base(library)
        return {
          ...based,
          ...SCALE[id]?.(based),
          name: `${library.name} ${LONG_LIBRARY_SUFFIX}`.slice(0, 120),
        }
      }
    case 'empty':
      return (library) => ({ ...base(library), documentCount: 0 })
    case 'failed':
      return (library) => ({
        ...base(library),
        lastScheduledRunsFailed: Boolean(library.schedule),
      })
    default:
      return base
  }
}

const originals = new Map<string, LibraryResponse>()
let applied = 'default'

/** The private fixture library; the scenarios give it documents and runs like the others. */
const PRIVATE_LIBRARY_ID = 'library-private-ablage'

/** Registers the detail lab's own libraries (upload, web directory, feed) once. */
function registerLabLibraries() {
  for (const detail of detailLabLibraries()) {
    if (mockLibraryDetails[detail.id]) continue
    mockLibraryDetails[detail.id] = detail
    mockLibraries.push({
      id: detail.id,
      name: detail.name,
      description: detail.description,
      ownerType: detail.ownerType,
      reach: detail.reach,
      myRole: detail.myRole,
      sourceType: detail.sourceType,
      lastIndexedAt: detail.sourceType === 'UPLOAD' ? undefined : SOURCE_LIBRARY_LAST_INDEXED,
      documentCount: 0,
      createdAt: detail.createdAt,
      updatedAt: detail.updatedAt,
    })
  }
}

/**
 * Brings the fixture libraries in line with the active scenario and `?mockRole=`; cheap to call
 * from every handler that reads libraries. Switching scenarios restores the regular fixtures
 * first. Outside `default` it also fills documents, folders and grants (detailScenarioFixtures).
 */
export function applyLibraryScenario(): void {
  const scenario = currentMockScenario()
  const role = currentMockRole()
  const key = `${scenario}/${role ?? ''}`
  if (key === applied) return
  if (scenario !== 'default' || role) registerLabLibraries()
  const ids = [
    ...Object.values(SOURCE_LIBRARY_IDS),
    ...detailLabLibraries().map((library) => library.id),
    PRIVATE_LIBRARY_ID,
  ]
  for (const id of ids) {
    const current = mockLibraryDetails[id]
    if (!current) continue
    if (!originals.has(id)) originals.set(id, structuredClone(current))
    const original = structuredClone(originals.get(id)!)
    const overlay = overlayFor(scenario, id)
    const next: LibraryResponse = overlay ? { ...original, ...overlay(original) } : original
    if (role && !next.privateLibrary) {
      next.myRole = role
      next.diagnosticsLockToggleable = role === 'OWNER'
    }
    if (next.privateLibrary && scenario !== 'default') {
      next.sourceSettings = { ...next.sourceSettings, folders: ['/Dokumente', '/Fortbildung'] }
      // the page puts „Zugang“ before the name itself
      if (next.connectionProfile) {
        next.connectionProfile = { ...next.connectionProfile, name: 'Nextcloud intern' }
      }
    }
    // the backend always names the owner; the fixtures leave it out
    if (scenario !== 'default') next.ownerName ??= 'Erika Muster'
    const documents = scenarioDocuments(next, scenario)
    if (documents) {
      mockLibraryDocuments[id] = documents
      next.documentCount = documents.length
      // an upload library's storage is what its documents take; in `failed` they fill a small
      // quota of 50 MB to the brim
      if (next.storageQuotaBytes != null && scenario === 'failed') {
        next.storageQuotaBytes = 50 * 1024 ** 2
        next.storageUsedBytes = next.storageQuotaBytes
      } else if (next.storageQuotaBytes != null) {
        next.storageUsedBytes = Math.min(
          documents.reduce((sum, document) => sum + (document.fileSize ?? 0), 0),
          Math.round(next.storageQuotaBytes * 0.9),
        )
      }
    }
    const folders = scenarioFolders(next, scenario)
    if (folders) mockLibraryFolders[id] = folders
    const grants = scenarioGrants(scenario)
    if (grants && !next.privateLibrary) mockLibraryGrants[id] = grants
    mockLibraryDetails[id] = next
    const listed = mockLibraries.find((entry) => entry.id === id)
    if (listed) {
      listed.name = next.name
      listed.myRole = next.myRole
      listed.ownerName = next.ownerName
      listed.documentCount = next.documentCount ?? 0
      if (next.sourceType !== 'UPLOAD') {
        listed.lastIndexedAt = scenario === 'empty' ? undefined : SOURCE_LIBRARY_LAST_INDEXED
      }
    }
  }
  applied = key
}

/** Failures of a single document; only these count as „fehlgeschlagen“. */
const ERROR_KINDS: Array<{ category: EventCategory; message: string }> = [
  {
    category: 'UNSUPPORTED_FORMAT',
    message: 'Dateiformat wird nicht unterstützt (application/x-msaccess)',
  },
  { category: 'REJECTED', message: 'Vom Quellserver abgewiesen (HTTP 403)' },
  { category: 'ERROR', message: 'Datei ist größer als 200 MB und wurde übersprungen' },
]

/** Notes about a run: removals and throttling, never failures. */
const NOTE_KINDS: Array<{ category: EventCategory; message: string }> = [
  { category: 'REMOVED', message: 'In der Quelle gelöscht, aus dem Bestand entfernt' },
  { category: 'RATE_LIMITED', message: 'Die Quelle hat gedrosselt, Abruf wiederholt' },
]

type Run = IndexingRunListResponse['runs'][number]
type EventCategory = Run['events'][number]['category']

/** The backend keeps at most this many events per run (IndexingRunEventRecorder). */
const EVENT_CAP = 500

interface RunSpec {
  status: 'COMPLETED' | 'FAILED' | 'RUNNING'
  /** Documents that failed - exactly as many error events. */
  failed?: number
  /** Notes (removed, throttled) besides the failures. */
  notes?: number
  message?: string
  failureCategory?: string | null
  incomplete?: boolean
  /** Areas of the scope the run could not read (see `unreadableShape`). */
  unreadable?: number
  minutes?: number
}

/** What a run knows of its library: size, kind of source and whether a schedule triggers it. */
interface RunContext {
  documents: number
  library?: LibraryResponse
}

/** Libraries whose run reports unreadable areas as a count plus one UNREACHABLE event each. */
const COUNTED_AREAS = new Set(['FILESYSTEM', 'HTTP_DIRECTORY', 'RSS_FEED'])

/** The levels of a place, one separator for every source: „Ebene / Ebene / Datei“. */
function place(...levels: Array<string | null | undefined>): string {
  return levels
    .flatMap((level) => (level ?? '').split('/'))
    .map((level) => level.trim())
    .filter(Boolean)
    .join(' / ')
}

/** Where an event points, built from the library's own scope; relative to the source's root. */
export function eventReference(library: LibraryResponse | undefined, e: number): string {
  const settings = (library?.sourceSettings ?? {}) as Record<string, unknown>
  const pick = <T>(key: string): T | undefined => {
    const list = (settings[key] as T[] | undefined) ?? []
    return list.length ? list[e % list.length] : undefined
  }
  const name = scenarioName('Vorgang', e, false)
  const slug = name.replace(/ /g, '-').toLowerCase()
  switch (library?.sourceType) {
    case 'HTTP_DIRECTORY':
    case 'RSS_FEED':
      return `${entryBase(library)}${library.sourceType === 'RSS_FEED' ? 'meldung' : 'richtlinie'}-${e}.html`
    case 'CONFLUENCE': {
      // a space, one of its pages, an attachment of that page
      const space = pick<{ key: string; name?: string | null }>('spaces')
      return place(
        space?.name ?? space?.key ?? 'Bauamt',
        CONFLUENCE_PAGES[e % CONFLUENCE_PAGES.length],
        `Anlage-${e}.pdf`,
      )
    }
    case 'S3': {
      const scope = pick<{ bucket: string; prefix?: string | null }>('scopes')
      return place(scope?.bucket ?? 'protokolle', scope?.prefix, `${slug}.pdf`)
    }
    case 'SHAREPOINT': {
      const entry = pick<{ name: string }>('libraries')
      return place(entry?.name ?? 'Dokumente', name, `Anlage-${e}.pdf`)
    }
    case 'GOOGLE_DRIVE': {
      const scope = pick<{ name?: string | null }>('scopes')
      return place(scope?.name ?? 'Ablage Projektteam', name, `Anlage-${e}.pdf`)
    }
    case 'FILESYSTEM':
      return place(name, `Anlage-${e}.pdf`)
    default:
      return place(pick<string>('folders'), name, `Anlage-${e}.pdf`)
  }
}

/** The place of an unreadable area for sources that only count them (directory, URL). */
function unreadableAreaReference(library: LibraryResponse | undefined, n: number): string {
  const area = ['Archiv-2019', 'Personalakten'][n % 2]
  if (library?.sourceType === 'FILESYSTEM') return area
  return `${library ? entryBase(library) : 'https://example.org/'}${area.toLowerCase()}/`
}

function runOf(i: number, spec: RunSpec, { documents, library }: RunContext): Run {
  const scheduled = Boolean(library?.schedule && library.schedule.frequency !== 'DISABLED')
  const byHand = !scheduled || i % 3 === 0 || i === 0
  const day = i === 0 ? 24 : 25 - i
  const started = i === 0 ? berlin(24, 11, 5) : byHand ? berlin(day, 10, 14) : berlin(day, 6, 0)
  const failed = spec.failed ?? 0
  const ended = spec.status === 'FAILED'
  const running = spec.status === 'RUNNING'
  const seconds = ended
    ? 4
    : 60 * (spec.minutes ?? Math.max(3, Math.round(documents / 300)) + (i % 4))
  const completed = new Date(started.getTime() + seconds * 1000)
  const counted = library ? COUNTED_AREAS.has(library.sourceType) : false
  const unreadable = spec.unreadable ?? 0
  const errorEvents = ended ? 0 : failed
  const scopeEvents = ended || !counted ? 0 : unreadable
  const noteEvents = ended ? 0 : (spec.notes ?? 0)
  const total = errorEvents + scopeEvents + noteEvents
  const live = running && library ? runningStatus(library.id, documents) : null
  const read = live ? live.totalDocuments : ended ? 0 : documents + failed + 3
  const processed = live ? live.documentCount : ended ? 0 : read - failed - 3
  const events: Run['events'] = []
  for (let e = 1; e <= Math.min(total, EVENT_CAP); e++) {
    if (e <= errorEvents) {
      events.push({ ...ERROR_KINDS[e % ERROR_KINDS.length], reference: eventReference(library, e) })
    } else if (e <= errorEvents + scopeEvents) {
      const n = e - errorEvents - 1
      events.push({
        category: 'UNREACHABLE',
        message: 'Bereich nicht lesbar (Zugriff verweigert)',
        reference: unreadableAreaReference(library, n),
      })
    } else {
      events.push({ ...NOTE_KINDS[e % NOTE_KINDS.length], reference: eventReference(library, e) })
    }
  }
  return {
    id: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`,
    status: spec.status,
    triggeredBy: byHand ? 'MANUAL' : 'SCHEDULED',
    runMode: i % 7 === 0 && i > 0 ? 'FULL' : 'INCREMENTAL',
    documentCount: processed,
    totalDocuments: read,
    documentsSkipped: live ? live.documentsSkipped : ended ? 0 : 3,
    documentsFailed: ended ? 0 : failed,
    documentsIndexedTotal: processed,
    message: spec.message ?? (running ? 'Indizierung läuft' : 'Indizierung abgeschlossen'),
    failureCategory: spec.failureCategory ?? null,
    incomplete: spec.incomplete ?? null,
    unreadableScopeCount: counted && unreadable > 0 ? unreadable : null,
    unlistedScopeKeys:
      !counted && unreadable > 0 && library ? unlistedKeysOf(library).slice(0, unreadable) : null,
    startedAt: started.toISOString(),
    completedAt: running ? null : completed.toISOString(),
    // a run refused at the sign-in sent nothing worth counting
    metrics: ended
      ? undefined
      : {
          requestsSent: read * 2 + i,
          throttleCount: i % 4 === 0 ? 3 : 0,
          throttleWaitSeconds: i % 4 === 0 ? 45 : 0,
          attachmentsProcessed: Math.round(read / 10),
          attachmentsSkipped: 1,
          attachmentsFailed: 0,
          bytesDownloaded: read * 512 * 1024,
        },
    events,
    eventsTruncatedCount: Math.max(0, total - EVENT_CAP),
  }
}

const REFUSED = 'Lauf abgebrochen: Die Quelle hat die Anmeldung abgelehnt (HTTP 401).'
const UNREACHABLE = 'Lauf abgebrochen: Die Quelle ist nicht erreichbar (HTTP 502).'

/** How a failed run of the library ends: a refused sign-in, or - without one - no answer. */
function failedRun(library: LibraryResponse | undefined): RunSpec {
  const signsIn = library?.sourceType !== 'HTTP_DIRECTORY' && library?.sourceType !== 'RSS_FEED'
  return signsIn
    ? { status: 'FAILED', message: REFUSED, failureCategory: 'CREDENTIALS_REJECTED' }
    : { status: 'FAILED', message: UNREACHABLE, failureCategory: null }
}

/** The run history of a library under the active scenario; an upload library has none. */
export function scenarioIndexingRuns(libraryId?: string): IndexingRunListResponse {
  const scenario = currentMockScenario()
  if (
    scenario !== 'default' &&
    libraryId &&
    mockLibraryDetails[libraryId]?.sourceType === 'UPLOAD'
  ) {
    return { runs: [] }
  }
  // the runs read as many documents as the library holds
  const library = libraryId ? mockLibraryDetails[libraryId] : undefined
  const documents = (libraryId ? mockLibraryDocuments[libraryId]?.length : undefined) ?? 1340
  const run = (i: number, spec: RunSpec) => runOf(i, spec, { documents, library })
  switch (scenario) {
    case 'small':
      return {
        runs: [run(1, { status: 'COMPLETED' }), run(2, { status: 'COMPLETED', notes: 1 })],
      }
    case 'scale':
      // the full retention: ten runs, one beyond the event cap, mixed outcomes
      return {
        runs: generate(10, (i) => {
          if (i === 1) return run(i, { status: 'COMPLETED', failed: 38, notes: 1702 })
          if (i === 4) return run(i, failedRun(library))
          if (i === 6) return run(i, { status: 'COMPLETED', incomplete: true, notes: 26 })
          return run(i, { status: 'COMPLETED', failed: i % 2 ? 12 : 0, notes: i % 2 ? 128 : 3 })
        }),
      }
    case 'empty':
      return { runs: [] }
    case 'failed':
      // three refused runs after the last successful one of 21.10., whose documents remain
      return {
        runs: [...generate(3, (i) => run(i, failedRun(library))), run(4, { status: 'COMPLETED' })],
      }
    case 'partial':
      return {
        runs: [
          run(1, { status: 'COMPLETED', failed: 17, notes: 4, unreadable: 1 }),
          run(2, { status: 'COMPLETED' }),
        ],
      }
    case 'slow':
      return {
        runs: [run(0, { status: 'RUNNING', notes: 2 }), run(1, { status: 'COMPLETED', notes: 2 })],
      }
    default:
      return mockIndexingRuns
  }
}
