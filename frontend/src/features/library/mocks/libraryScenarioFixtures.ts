import type { IndexingRunListResponse, LibraryResponse } from '@/shared/api/types'
import {
  LONG_COMPOUND,
  currentMockScenario,
  generate,
  scenarioName,
  type MockScenario,
} from '@/shared/mocks/mockScenario'
import { mockIndexingRuns } from './indexingFixtures'
import { mockLibraries, mockLibraryDetails } from './libraryFixtures'
import { SOURCE_LIBRARY_IDS, SOURCE_LIBRARY_LAST_INDEXED } from './sourceLibraryFixtures'

/**
 * The connected-library fixtures under the fixture scenarios: `scale` widens every selection to
 * dozens or hundreds of entries with long names, `small` narrows it to one, `empty` leaves the
 * libraries without documents and runs, `failed` and `partial` change their run history.
 */

type Overlay = (library: LibraryResponse) => Partial<LibraryResponse>

const LONG_LIBRARY_SUFFIX = `– ${LONG_COMPOUND}n, Bauleitplanung und Übergabeordner der Ämter (2009–2026)`

function deepPath(i: number, depth: number) {
  return `/${generate(depth, (level) => scenarioName(['Ordner', 'Vorgang', 'Akte', 'Jahrgang'][level % 4], i + level)).join('/')}`
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
      spaces: generate(120, (i) => ({ key: `SP${i}`, name: scenarioName('Space', i) })),
      fullSyncIntervalDays: 7,
    },
  }),
  [SOURCE_LIBRARY_IDS.SHAREPOINT]: () => ({
    sourceSettings: {
      libraries: generate(25, (i) => ({
        driveId: `gen-drive-${((i - 1) % 17) + 1}-${i}`,
        name: `${scenarioName('Site', ((i - 1) % 17) + 1)} · ${scenarioName('Dokumentbibliothek', i)}`,
        folders: generate(i % 5 === 0 ? 12 : 2, (f) => ({
          id: `gen-drive-${((i - 1) % 17) + 1}-${i}_F${f}-${f}-${f}`,
          name: scenarioName('Ordner', f),
        })),
      })),
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
  [SOURCE_LIBRARY_IDS.CONFLUENCE]: (library) => ({
    sourceSettings: { ...library.sourceSettings, spaces: [{ key: 'BAU', name: 'Bauamt' }] },
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
        documentCount: 18342,
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
let applied: MockScenario = 'default'

/**
 * Brings the connected-library fixtures in line with the active scenario; cheap to call from every
 * handler that reads libraries. Switching scenarios restores the regular fixtures first.
 */
export function applyLibraryScenario(): void {
  const scenario = currentMockScenario()
  if (scenario === applied) return
  for (const id of Object.values(SOURCE_LIBRARY_IDS)) {
    const current = mockLibraryDetails[id]
    if (!current) continue
    if (!originals.has(id)) originals.set(id, structuredClone(current))
    const original = structuredClone(originals.get(id)!)
    const overlay = overlayFor(scenario, id)
    const next = overlay ? { ...original, ...overlay(original) } : original
    mockLibraryDetails[id] = next
    const listed = mockLibraries.find((entry) => entry.id === id)
    if (listed) {
      listed.name = next.name
      listed.documentCount = next.documentCount ?? 0
      listed.lastIndexedAt = scenario === 'empty' ? undefined : SOURCE_LIBRARY_LAST_INDEXED
    }
  }
  applied = scenario
}

const EVENT_MESSAGES = [
  'Vom Quellserver abgewiesen (HTTP 403)',
  'Dateiformat wird nicht unterstützt (application/x-msaccess)',
  'Datei ist größer als 200 MB und wurde übersprungen',
  'Zeitüberschreitung beim Abruf (30 Sekunden)',
]

function run(
  i: number,
  status: 'COMPLETED' | 'FAILED',
  failed: number,
  events: number,
  message: string,
): IndexingRunListResponse['runs'][number] {
  const started = new Date(Date.UTC(2026, 9, 24 - i, 4, 0)).toISOString()
  const completed = new Date(Date.UTC(2026, 9, 24 - i, 4, 7)).toISOString()
  const shown = Math.min(events, 20)
  return {
    id: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`,
    status,
    triggeredBy: i % 3 === 0 ? 'MANUAL' : 'SCHEDULED',
    runMode: i % 7 === 0 ? 'FULL' : 'INCREMENTAL',
    documentCount: status === 'FAILED' ? 0 : 214 - failed,
    totalDocuments: status === 'FAILED' ? 0 : 214,
    documentsSkipped: 3,
    documentsFailed: failed,
    documentsIndexedTotal: status === 'FAILED' ? 0 : 214 - failed,
    message,
    startedAt: started,
    completedAt: completed,
    events: generate(shown, (e) => ({
      category: e % 2 === 0 ? 'REJECTED' : 'UNREACHABLE',
      message: EVENT_MESSAGES[e % EVENT_MESSAGES.length],
      reference: `/Projekte/${scenarioName('Vorgang', e)}/Anlage-${e}.pdf`,
    })),
    eventsTruncatedCount: events - shown,
  }
}

/** The run history of a library under the active scenario. */
export function scenarioIndexingRuns(): IndexingRunListResponse {
  switch (currentMockScenario()) {
    case 'scale':
      return {
        runs: generate(10, (i) =>
          run(i, 'COMPLETED', i % 2 ? 12 : 0, i % 2 ? 140 : 3, 'Indizierung abgeschlossen'),
        ),
      }
    case 'empty':
      return { runs: [] }
    case 'failed':
      return {
        runs: generate(3, (i) =>
          run(
            i,
            'FAILED',
            0,
            0,
            'Lauf abgebrochen: Die Quelle hat die Anmeldung abgelehnt (HTTP 401).',
          ),
        ),
      }
    case 'partial':
      return {
        runs: [
          run(
            1,
            'COMPLETED',
            17,
            17,
            'Indizierung abgeschlossen: 197 verarbeitet, 17 fehlgeschlagen',
          ),
          run(2, 'COMPLETED', 0, 0, 'Indizierung abgeschlossen'),
        ],
      }
    default:
      return mockIndexingRuns
  }
}
