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
import { mockDocumentMetadata } from './libraryMetadataFixtures'
import { scenarioSpaces, siteName } from './sourceScenarioMock'
import {
  mockLibraries,
  mockLibraryDetails,
  mockLibraryDocuments,
  mockLibraryFolders,
} from './libraryFixtures'
import { mockLibraryGrants } from '@/features/asset/mocks/assetFixtures'
import {
  runningStatus,
  detailLabLibraries,
  feedBase,
  scenarioDocumentMetadata,
  scenarioMetadataFields,
  scenarioDocuments,
  scenarioFolders,
  scenarioGrants,
} from './detailScenarioFixtures'
import { SOURCE_LIBRARY_IDS, SOURCE_LIBRARY_LAST_INDEXED } from './sourceLibraryFixtures'

/**
 * The connected-library fixtures under the fixture scenarios: `scale` widens every selection to
 * dozens or hundreds of entries with long names, `small` narrows it to one, `empty` leaves the
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
    sourceSettings: { folders: generate(50, (i) => deepPath(i, (i % 8) + 1)), teamSpace: true },
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

const SMALL: Record<string, Overlay> = {
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
      ],
    },
  }),
  [SOURCE_LIBRARY_IDS.CONFLUENCE]: (library) => ({
    sourceSettings: {
      ...library.sourceSettings,
      spaces: scenarioSpaces(1).map((space) => ({ key: space.key, name: space.name ?? null })),
    },
  }),
  [SOURCE_LIBRARY_IDS.NEXTCLOUD]: () => ({ sourceSettings: { folders: ['/'] } }),
  [SOURCE_LIBRARY_IDS.SMB]: () => ({ sourceSettings: { folders: ['/'] } }),
  [SOURCE_LIBRARY_IDS.DROPBOX]: () => ({ sourceSettings: { folders: ['/'], teamSpace: false } }),
  [SOURCE_LIBRARY_IDS.GOOGLE_DRIVE]: (library) => ({
    sourceSettings: {
      ...library.sourceSettings,
      scopes: [{ drive: '0AMockDrive', name: 'Projektablage' }],
    },
  }),
  [SOURCE_LIBRARY_IDS.S3]: (library) => ({
    sourceSettings: { ...library.sourceSettings, scopes: [{ bucket: 'protokolle', prefix: null }] },
  }),
}

function overlayFor(scenario: MockScenario, id: string): Overlay | null {
  switch (scenario) {
    case 'scale':
      return (library) => ({
        ...SCALE[id]?.(library),
        name: `${library.name} ${LONG_LIBRARY_SUFFIX}`.slice(0, 120),
      })
    case 'small':
      return SMALL[id] ?? null
    case 'empty':
      // an empty upload library holds no bytes either
      return (library) => ({
        documentCount: 0,
        ...(library.storageQuotaBytes ? { storageUsedBytes: 0 } : {}),
      })
    case 'failed':
      // an upload library under `failed` has used up its quota
      return (library) => ({
        lastScheduledRunsFailed: true,
        ...(library.storageQuotaBytes ? { storageUsedBytes: library.storageQuotaBytes } : {}),
      })
    default:
      return null
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
    }
    // the backend always names the owner; the fixtures leave it out
    if (scenario !== 'default') next.ownerName ??= 'Erika Muster'
    // the next planned run at the schedule's own hour, in the time zone the page shows
    if (scenario !== 'default' && next.schedule?.nextRunAt) {
      const { hour = 6, minute = 0 } = next.schedule
      next.schedule = {
        ...next.schedule,
        nextRunAt: new Date(2026, 9, 25, hour ?? 6, minute ?? 0).toISOString(),
      }
    }
    const documents = scenarioDocuments(next, scenario)
    if (documents) {
      mockLibraryDocuments[id] = documents
      next.documentCount = documents.length
      Object.assign(
        mockDocumentMetadata,
        scenarioDocumentMetadata(documents, scenarioMetadataFields(scenario) ?? []),
      )
      if (next.sourceType === 'UPLOAD') {
        // the last upload and the bytes the documents take
        next.updatedAt = new Date(2026, 9, 24, 9, 30).toISOString()
        if (next.storageQuotaBytes && next.storageUsedBytes !== next.storageQuotaBytes) {
          const bytes = documents.reduce((sum, doc) => sum + (doc.fileSize ?? 0), 0)
          // a full quota is the `failed` scenario's alone
          next.storageUsedBytes = Math.min(bytes, Math.round(next.storageQuotaBytes * 0.8))
        }
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

const EVENT_KINDS: Array<{
  category: IndexingRunListResponse['runs'][number]['events'][number]['category']
  message: string
}> = [
  { category: 'REJECTED', message: 'Vom Quellserver abgewiesen (HTTP 403)' },
  {
    category: 'UNSUPPORTED_FORMAT',
    message: 'Dateiformat wird nicht unterstützt (application/x-msaccess)',
  },
  { category: 'ERROR', message: 'Datei ist größer als 200 MB und wurde übersprungen' },
  { category: 'UNREACHABLE', message: 'Zeitüberschreitung beim Abruf (30 Sekunden)' },
  { category: 'REMOVED', message: 'In der Quelle gelöscht, aus dem Bestand entfernt' },
  { category: 'RATE_LIMITED', message: 'Die Quelle hat gedrosselt, Abruf wiederholt' },
]

/** The backend keeps at most this many events per run (IndexingRunEventRecorder). */
const EVENT_CAP = 500

type Run = IndexingRunListResponse['runs'][number]

interface RunSpec {
  status: 'COMPLETED' | 'FAILED' | 'RUNNING'
  failed?: number
  events?: number
  message?: string
  failureCategory?: string
  incomplete?: boolean
  unreadableScopeCount?: number
  minutes?: number
}

/** What a run knows of its library: size, kind of source and whether a schedule triggers it. */
interface RunContext {
  documents: number
  library?: LibraryResponse
}

/** Where an event points, in the form the source names its entries, inside the readable scope. */
function eventReference(library: LibraryResponse | undefined, e: number): string {
  const name = scenarioName('Vorgang', e)
  const settings = (library?.sourceSettings ?? {}) as Record<string, unknown>
  // the last entry of the selection: under `partial` the first one is the unreadable one
  const last = <T>(key: string): T | undefined => {
    const list = (settings[key] as T[] | undefined) ?? []
    return list[list.length - 1]
  }
  switch (library?.sourceType) {
    case 'HTTP_DIRECTORY':
      return `${library.sourceUrl ?? 'https://example.org/'}richtlinie-${e}.html`
    case 'RSS_FEED':
      return `${feedBase(library)}eintrag-${e}.html`
    case 'CONFLUENCE': {
      const space = last<{ name?: string | null; key: string }>('spaces')
      return `${space?.name ?? space?.key ?? 'Bauamt'} › ${name} › Anhang-${e}.pdf`
    }
    case 'S3': {
      const scope = last<{ bucket: string; prefix?: string | null }>('scopes')
      return `${scope?.bucket ?? 'protokolle'}/${scope?.prefix ?? ''}${name.replace(/ /g, '-').toLowerCase()}.pdf`
    }
    case 'SHAREPOINT': {
      const entry = last<{ name?: string }>('libraries')
      return `${entry?.name ?? 'Dokumente (Rathaus)'} › ${name}/Anlage-${e}.pdf`
    }
    case 'GOOGLE_DRIVE': {
      const scope = last<{ name?: string }>('scopes')
      return `${scope?.name ?? 'Projektablage'} › ${name}/Anlage-${e}.pdf`
    }
    case 'FILESYSTEM':
      return `${library.sourcePath ?? '/srv/opaa'}/${name}/Anlage-${e}.pdf`
    default: {
      const folder = last<string>('folders') ?? '/'
      return `${folder.replace(/\/$/, '')}/${name}/Anlage-${e}.pdf`
    }
  }
}

/** Sources without a sign-in: a refused run is a source that could not be reached. */
const NO_SIGN_IN = new Set(['FILESYSTEM', 'HTTP_DIRECTORY', 'RSS_FEED'])

function runOf(i: number, spec: RunSpec, { documents, library }: RunContext): Run {
  // scheduled runs start at the schedule's hour; without a schedule every run was started by hand
  const hour = library?.schedule?.hour ?? 6
  const scheduled = Boolean(library?.schedule && library.schedule.frequency !== 'DISABLED')
  const manual = !scheduled || i % 3 === 0
  // a run by hand starts at an uneven time of the working day
  const started = manual
    ? new Date(2026, 9, 24 - i, 9 + (i % 6), (i * 17) % 60)
    : new Date(2026, 9, 24 - i, hour, library?.schedule?.minute ?? 0)
  const failed = spec.failed ?? 0
  const ended = spec.status === 'FAILED'
  const running = spec.status === 'RUNNING'
  // a run refused at the start ends after seconds
  const completed = new Date(started.getTime() + (ended ? 3_000 : (spec.minutes ?? 7) * 60_000))
  const events = ended ? 0 : (spec.events ?? 0)
  const live =
    running && library
      ? runningStatus(
          library.id,
          library.sourceType === 'RSS_FEED',
          mockLibraryDocuments[library.id]?.length,
        )
      : null
  const total = live ? live.totalDocuments : ended ? 0 : documents + failed + 3
  const processed = live ? live.documentCount : ended ? 0 : total - failed - 3
  // one error event per failed document, the rest are notes (removals, throttling); the backend
  // keeps at most EVENT_CAP of them and counts the others
  const errorEvents = Math.min(failed, events)
  const noteEvents = Math.max(0, events - failed)
  const shownErrors = Math.min(errorEvents, EVENT_CAP)
  const shownNotes = Math.min(noteEvents, EVENT_CAP - shownErrors)
  const unreachable = ended && NO_SIGN_IN.has(library?.sourceType ?? '')
  const fullDaily =
    (library?.sourceSettings as { fullSyncIntervalDays?: number } | null)?.fullSyncIntervalDays ===
    1
  return {
    id: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`,
    status: spec.status,
    triggeredBy: manual ? 'MANUAL' : 'SCHEDULED',
    runMode: fullDaily || i % 7 === 0 ? 'FULL' : 'INCREMENTAL',
    documentCount: processed,
    totalDocuments: total,
    documentsSkipped: live ? live.documentsSkipped : ended ? 0 : 3,
    documentsFailed: failed,
    documentsIndexedTotal: processed,
    message: unreachable
      ? UNREACHABLE
      : (spec.message ?? (running ? 'Indizierung läuft' : 'Indizierung abgeschlossen')),
    failureCategory: unreachable ? null : (spec.failureCategory ?? null),
    incomplete: spec.incomplete ?? null,
    unreadableScopeCount: spec.unreadableScopeCount ?? null,
    startedAt: started.toISOString(),
    completedAt: running ? null : completed.toISOString(),
    // a run refused at the sign-in sent nothing worth counting
    metrics: ended
      ? undefined
      : {
          requestsSent: total * 2 + i,
          throttleCount: i % 4 === 0 ? 3 : 0,
          throttleWaitSeconds: i % 4 === 0 ? 45 : 0,
          attachmentsProcessed: Math.round(total / 10),
          attachmentsSkipped: 1,
          attachmentsFailed: failed > 0 ? 2 : 0,
          bytesDownloaded: total * 512 * 1024,
        },
    events: [
      ...generate(shownErrors, (e) => ({
        ...EVENT_KINDS[(e - 1) % 4],
        reference: eventReference(library, e),
      })),
      ...generate(shownNotes, (e) => ({
        ...EVENT_KINDS[4 + (e % 2)],
        reference: eventReference(library, shownErrors + e),
      })),
    ],
    eventsTruncatedCount: errorEvents + noteEvents - shownErrors - shownNotes,
  }
}

const UNREACHABLE = 'Lauf abgebrochen: Die Quelle war nicht erreichbar.'

const REFUSED = 'Lauf abgebrochen: Die Quelle hat die Anmeldung abgelehnt (HTTP 401).'

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
      return { runs: [run(1, { status: 'COMPLETED' }), run(2, { status: 'COMPLETED', events: 1 })] }
    case 'scale':
      // the full retention: ten runs, one at the event cap, mixed outcomes
      return {
        runs: generate(10, (i) => {
          if (i === 1)
            return run(i, { status: 'COMPLETED', failed: 38, events: 1740, minutes: 192 })
          if (i === 4)
            return run(i, {
              status: 'FAILED',
              message: REFUSED,
              failureCategory: 'CREDENTIALS_REJECTED',
            })
          if (i === 6) {
            return run(i, {
              status: 'COMPLETED',
              incomplete: true,
              unreadableScopeCount: 4,
              events: 26,
            })
          }
          return run(i, { status: 'COMPLETED', failed: i % 2 ? 12 : 0, events: i % 2 ? 140 : 3 })
        }),
      }
    case 'empty':
      return { runs: [] }
    case 'failed':
      return {
        runs: generate(3, (i) =>
          run(i, { status: 'FAILED', message: REFUSED, failureCategory: 'CREDENTIALS_REJECTED' }),
        ),
      }
    case 'partial':
      return {
        runs: [
          run(1, {
            status: 'COMPLETED',
            failed: 17,
            events: 17,
            // one of the selection could not be read (unlistedKeysOf)
            unreadableScopeCount: 1,
          }),
          run(2, { status: 'COMPLETED' }),
        ],
      }
    case 'slow':
      return {
        runs: [
          run(0, { status: 'RUNNING', events: 4 }),
          run(1, { status: 'COMPLETED', events: 2 }),
        ],
      }
    default:
      return mockIndexingRuns
  }
}
