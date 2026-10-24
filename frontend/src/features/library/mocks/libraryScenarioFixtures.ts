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
  detailLabLibraries,
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
      return () => ({ documentCount: 0 })
    case 'failed':
      return () => ({ lastScheduledRunsFailed: true })
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
    if (role && !next.privateLibrary) next.myRole = role
    const documents = scenarioDocuments(next, scenario)
    if (documents) {
      mockLibraryDocuments[id] = documents
      next.documentCount = documents.length
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

function run(i: number, spec: RunSpec): Run {
  const started = new Date(Date.UTC(2026, 9, 24 - i, 4, 0))
  const minutes = spec.minutes ?? 7
  const completed = new Date(started.getTime() + minutes * 60_000)
  const events = spec.events ?? 0
  const shown = Math.min(events, EVENT_CAP)
  const failed = spec.failed ?? 0
  const ended = spec.status === 'FAILED'
  const total = ended ? 0 : 1340
  return {
    id: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`,
    status: spec.status,
    triggeredBy: i % 3 === 0 ? 'MANUAL' : 'SCHEDULED',
    runMode: i % 7 === 0 ? 'FULL' : 'INCREMENTAL',
    documentCount: ended ? 0 : total - failed - 3,
    totalDocuments: total,
    documentsSkipped: ended ? 0 : 3,
    documentsFailed: failed,
    documentsIndexedTotal: ended ? 0 : total - failed - 3,
    message: spec.message ?? 'Indizierung abgeschlossen',
    failureCategory: spec.failureCategory ?? null,
    incomplete: spec.incomplete ?? null,
    unreadableScopeCount: spec.unreadableScopeCount ?? null,
    startedAt: started.toISOString(),
    completedAt: spec.status === 'RUNNING' ? null : completed.toISOString(),
    metrics: {
      requestsSent: 1842 + i,
      throttleCount: i % 4 === 0 ? 3 : 0,
      throttleWaitSeconds: i % 4 === 0 ? 45 : 0,
      attachmentsProcessed: 120,
      attachmentsSkipped: 4,
      attachmentsFailed: failed > 0 ? 2 : 0,
      bytesDownloaded: 734 * 1024 ** 2,
    },
    events: generate(shown, (e) => {
      // a run without failures records only removals and throttling
      const kinds = failed > 0 || ended ? EVENT_KINDS : EVENT_KINDS.slice(4)
      const kind = kinds[e % kinds.length]
      return {
        ...kind,
        reference: `/Projekte/${scenarioName('Vorgang', e)}/Anlage-${e}.pdf`,
      }
    }),
    eventsTruncatedCount: events - shown,
  }
}

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
            return run(i, { status: 'FAILED', message: REFUSED, failureCategory: 'AUTH' })
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
          run(i, { status: 'FAILED', message: REFUSED, failureCategory: 'AUTH' }),
        ),
      }
    case 'partial':
      return {
        runs: [
          run(1, {
            status: 'COMPLETED',
            failed: 17,
            events: 17,
            unreadableScopeCount: 2,
            message: 'Indizierung abgeschlossen: 1320 verarbeitet, 17 fehlgeschlagen',
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
