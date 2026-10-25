import type {
  AssetGrantResponse,
  AssetRole,
  IndexingStatusResponse,
  LibraryDocumentResponse,
  LibraryMetadataFieldResponse,
  LibraryResponse,
} from '@/shared/api/types'
import { generate, scenarioName, type MockScenario } from '@/shared/mocks/mockScenario'
import type { MockLibraryFolder } from './libraryFixtures'

/**
 * What the detail page of a fixture library shows besides its source under the fixture
 * scenarios: documents (and folders of an upload library), grants, space associations, metadata
 * fields and the indexing status. `default` is never touched, so the unit tests keep their
 * fixtures. The volumes follow the backend's limits: 10 runs per library, 500 events per run,
 * 5 own metadata fields per library.
 */

/** Libraries the detail lab adds next to the connected fixtures: upload, web directory, feed. */
export const DETAIL_LAB_LIBRARY_IDS = {
  UPLOAD: 'library-upload-rundschreiben',
  HTTP_DIRECTORY: 'library-web-intranet',
  RSS_FEED: 'library-rss-presse',
} as const

const CREATED = '2026-09-10T08:00:00Z'

/** 24.10.2026 is the fixture's today; the page renders Europe/Berlin (summer time until 25.10.). */
export function berlin(day: number, hour: number, minute: number): Date {
  const offset = day >= 25 ? 1 : 2
  return new Date(Date.UTC(2026, 9, day, hour - offset, minute))
}

/** The fixture schedule: daily at 06:00 Berlin time, next run tomorrow. */
export const FIXTURE_SCHEDULE = {
  frequency: 'DAILY' as const,
  hour: 6,
  minute: 0,
  nextRunAt: berlin(25, 6, 0).toISOString(),
}

/** When the newest scheduled run indexed its documents (24.10.2026, 06:02 Berlin). */
export const FIXTURE_INDEXED_AT = berlin(24, 6, 2).toISOString()

/** The directory a web library's or feed's entries live under, always ending in a slash. */
export function entryBase(library: Pick<LibraryResponse, 'sourceUrl'>): string {
  const url = library.sourceUrl ?? 'https://example.org/'
  try {
    return new URL('./', url).toString()
  } catch {
    return url.endsWith('/') ? url : `${url}/`
  }
}

function labLibrary(
  id: string,
  name: string,
  description: string,
  sourceType: LibraryResponse['sourceType'],
  extra: Partial<LibraryResponse>,
): LibraryResponse {
  return {
    id,
    name,
    description,
    ownerType: 'USER',
    ownerId: 'mock-user-id',
    reach: { allAccounts: false, groupCount: 1, userCount: 1 },
    myRole: 'OWNER',
    documentCount: 0,
    sourceType,
    lastScheduledRunsFailed: false,
    diagnosticsLocked: false,
    diagnosticsLockToggleable: true,
    createdAt: CREATED,
    updatedAt: CREATED,
    ...extra,
  }
}

/** The lab libraries; registered only once a scenario is active (`applyLibraryScenario`). */
export function detailLabLibraries(): LibraryResponse[] {
  const daily = FIXTURE_SCHEDULE
  return [
    labLibrary(
      DETAIL_LAB_LIBRARY_IDS.UPLOAD,
      'Rundschreiben',
      'Hochgeladene Rundschreiben und Vermerke',
      'UPLOAD',
      {
        storageQuotaBytes: 2 * 1024 ** 3,
        storageUsedBytes: 734 * 1024 ** 2,
        updatedAt: UPLOADED_AT,
      },
    ),
    labLibrary(
      DETAIL_LAB_LIBRARY_IDS.HTTP_DIRECTORY,
      'Intranet-Richtlinien',
      'Verzeichnis der Richtlinien im Intranet',
      'HTTP_DIRECTORY',
      { sourceUrl: 'https://intranet.rheinfurt.example/richtlinien/', schedule: daily },
    ),
    labLibrary(
      DETAIL_LAB_LIBRARY_IDS.RSS_FEED,
      'Pressemitteilungen',
      'Feed der Pressestelle',
      'RSS_FEED',
      { sourceUrl: 'https://www.rheinfurt.example/presse/feed.xml', schedule: daily },
    ),
  ]
}

const DOCUMENT_ERRORS = [
  'Die Datei ist verschlüsselt und lässt sich nicht lesen.',
  'Die Datei enthält keinen extrahierbaren Text.',
  'Das Dateiformat wird nicht unterstützt (application/x-msaccess).',
  'Die Datei ist größer als 200 MB.',
  'Zeitüberschreitung beim Abruf aus der Quelle.',
]

const EXTENSIONS = [
  ['pdf', 'application/pdf'],
  ['docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
  ['xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'],
  ['msg', 'application/vnd.ms-outlook'],
  ['html', 'text/html'],
] as const

/** When the upload library last took in files: the day before the fixture's today. */
const UPLOADED_AT = berlin(23, 14, 32).toISOString()

interface DocumentPlan {
  count: number
  failed: number
  pending: number
}

/**
 * A refused run (`failed`) leaves the documents as they were; the failed and pending ones are
 * spread over the list, so its first page is mixed, never all failed or all pending.
 */
function planFor(scenario: MockScenario): DocumentPlan | null {
  switch (scenario) {
    case 'small':
      return { count: 3, failed: 0, pending: 0 }
    case 'scale':
      return { count: 1250, failed: 38, pending: 14 }
    case 'empty':
      return { count: 0, failed: 0, pending: 0 }
    case 'failed':
      return { count: 12, failed: 0, pending: 0 }
    case 'partial':
      return { count: 214, failed: 17, pending: 0 }
    case 'slow':
      return { count: 26, failed: 0, pending: 9 }
    default:
      return null
  }
}

/** Whether the `i`-th document is the `n`-th of `share` spread evenly from `offset` on. */
function spread(i: number, share: number, count: number, offset: number): boolean {
  if (share === 0) return false
  const step = Math.max(2, Math.floor(count / share))
  return i >= offset && (i - offset) % step === 0 && (i - offset) / step < share
}

/** Where a connector document sits in its source, as the list shows it under the name. */
function hierarchyOf(library: LibraryResponse, i: number): Partial<LibraryDocumentResponse> {
  const settings = (library.sourceSettings ?? {}) as Record<string, unknown>
  switch (library.sourceType) {
    case 'CONFLUENCE': {
      const spaces = (settings.spaces as Array<{ key: string }> | undefined) ?? [{ key: 'BAU' }]
      const space = spaces[i % spaces.length].key
      return {
        sourceContainerKey: space,
        sourceHierarchyPath: `${scenarioName('Seite', (i % 9) + 1)} / ${scenarioName('Unterseite', i)}`,
      }
    }
    case 'HTTP_DIRECTORY':
    case 'RSS_FEED':
      return {
        sourceUrl: `${entryBase(library)}${library.sourceType === 'RSS_FEED' ? 'meldung' : 'richtlinie'}-${i}.html`,
      }
    case 'UPLOAD':
      return {}
    default:
      return {
        sourceHierarchyPath: generate((i % 6) + 1, (level) =>
          scenarioName(
            ['Ordner', 'Vorgang', 'Akte', 'Jahrgang', 'Teil', 'Band'][level - 1],
            i,
            level === 2,
          ),
        ).join('/'),
      }
  }
}

/** The documents of a fixture library under a scenario; null keeps the regular fixtures. */
export function scenarioDocuments(
  library: LibraryResponse,
  scenario: MockScenario,
): LibraryDocumentResponse[] | null {
  const plan = planFor(scenario)
  if (!plan) return null
  const upload = library.sourceType === 'UPLOAD'
  const folderCount = upload ? folderPlan(scenario) : 0
  return generate(plan.count, (i) => {
    const [extension, contentType] = EXTENSIONS[i % EXTENSIONS.length]
    const failed = spread(i, plan.failed, plan.count, 3)
    const pending = !failed && spread(i, plan.pending, plan.count, 2)
    const base = scenarioName(upload ? 'Rundschreiben' : 'Dokument', i).replace(/\//g, '-')
    return {
      id: `${library.id}-doc-${i}`,
      fileName: `${base}.${extension}`,
      contentType,
      fileSize: 18_000 + ((i * 7919) % 4_800_000),
      status: failed ? 'FAILED' : pending ? 'PENDING' : 'INDEXED',
      sourceType: library.sourceType,
      chunkCount: failed || pending ? 0 : (i % 40) + 1,
      indexedAt: failed || pending ? null : upload ? UPLOADED_AT : FIXTURE_INDEXED_AT,
      uploadedByUserId: upload ? 'mock-user-id' : null,
      errorMessage: failed ? DOCUMENT_ERRORS[i % DOCUMENT_ERRORS.length] : null,
      // an upload library files every fourth document into one of its folders
      folderId:
        upload && folderCount > 0 && i % 4 === 0
          ? `${library.id}-folder-${((i / 4 - 1) % folderCount) + 1}`
          : null,
      ...hierarchyOf(library, i),
    }
  })
}

function folderPlan(scenario: MockScenario): number {
  if (scenario === 'scale') return 60
  if (scenario === 'empty') return 0
  return 2
}

/** The folders of an upload library: root folders, the first with a nested chain. */
export function scenarioFolders(
  library: LibraryResponse,
  scenario: MockScenario,
): MockLibraryFolder[] | null {
  if (library.sourceType !== 'UPLOAD' || !planFor(scenario)) return null
  const roots = generate(folderPlan(scenario), (i) => ({
    id: `${library.id}-folder-${i}`,
    libraryId: library.id,
    parentFolderId: null,
    name: scenarioName('Ordner', i),
    createdAt: CREATED,
  }))
  const nested =
    roots.length === 0
      ? []
      : generate(scenario === 'scale' ? 7 : 1, (level) => ({
          id: `${library.id}-folder-1-${level}`,
          libraryId: library.id,
          parentFolderId: level === 1 ? roots[0].id : `${library.id}-folder-1-${level - 1}`,
          name: scenarioName('Unterordner', level),
          createdAt: CREATED,
        }))
  return [...roots, ...nested]
}

function grant(
  i: number,
  subjectType: AssetGrantResponse['subjectType'],
  name: string,
  role: AssetRole,
  expiresAt: string | null = null,
): AssetGrantResponse {
  return {
    id: `scenario-grant-${i}`,
    subjectType,
    subjectId: subjectType === 'ALL_ACCOUNTS' ? null : `scenario-subject-${i}`,
    subjectDisplayName: subjectType === 'ALL_ACCOUNTS' ? null : name,
    activeMemberCount: subjectType === 'GROUP' ? (i * 13) % 240 : null,
    emptyGroup: subjectType === 'GROUP' && i % 11 === 0,
    role,
    expiresAt,
    grantedByUserId: 'mock-user-id',
    grantedByDisplayName: 'Admin',
    createdAt: CREATED,
    updatedAt: CREATED,
  }
}

// the owner's own grant, named like the library's owner (ownerName in libraryScenarioFixtures)
const OWNER_GRANT = grant(0, 'USER', 'Erika Muster', 'OWNER')
const ROLES: AssetRole[] = ['VIEWER', 'VIEWER', 'EDITOR', 'VIEWER', 'MANAGER']

/** The grants of a fixture library under a scenario; null keeps the regular fixtures. */
export function scenarioGrants(scenario: MockScenario): AssetGrantResponse[] | null {
  switch (scenario) {
    case 'small':
    case 'failed':
      return [OWNER_GRANT, grant(1, 'GROUP', 'Bauamt', 'VIEWER')]
    case 'scale':
      return [
        OWNER_GRANT,
        ...generate(28, (i) =>
          grant(i, 'GROUP', scenarioName('Gruppe', i), ROLES[i % ROLES.length]),
        ),
        ...generate(16, (i) =>
          grant(
            100 + i,
            'USER',
            i % 5 === 0
              ? `Dr. Friederike von Grundstücksverkehrsgenehmigungsakte-Lindenberg ${i}`
              : `Person ${String(i).padStart(3, '0')}`,
            ROLES[(i + 2) % ROLES.length],
            i % 4 === 0 ? '2026-12-31T23:59:59.999Z' : null,
          ),
        ),
        grant(200, 'ALL_ACCOUNTS', '', 'VIEWER'),
      ]
    case 'empty':
      return [OWNER_GRANT]
    case 'partial':
    case 'slow':
      return [
        OWNER_GRANT,
        grant(1, 'GROUP', 'Bauamt', 'VIEWER'),
        grant(2, 'GROUP', 'Rechtsamt', 'EDITOR'),
        grant(3, 'USER', 'Erika Muster', 'MANAGER', '2026-12-31T23:59:59.999Z'),
      ]
    default:
      return null
  }
}

/**
 * The spaces a fixture library is associated with under a scenario; null keeps the fixtures. A
 * private library serves only its owner's own space, if any.
 */
export function scenarioSpaces(
  scenario: MockScenario,
  privateLibrary = false,
): Array<{ spaceId: string; spaceName: string }> | null {
  const count =
    scenario === 'small'
      ? 1
      : scenario === 'scale'
        ? 34
        : scenario === 'empty'
          ? 0
          : planFor(scenario)
            ? 3
            : null
  if (count === null) return null
  return generate(privateLibrary ? Math.min(count, 1) : count, (i) => ({
    spaceId: `scenario-space-${i}`,
    spaceName: scenarioName('Space', i),
  }))
}

function field(
  i: number,
  values: number,
  type: 'PATTERN' | 'SELECT' = 'SELECT',
): LibraryMetadataFieldResponse {
  return {
    fieldKey: `feld_${i}`,
    documentFieldKey: `lib:feld_${i}`,
    label: ['Aktenzeichen', 'Zuständiges Amt', 'Vorgangsart', 'Gemarkung', 'Aufbewahrungsfrist'][
      i - 1
    ],
    type,
    valuePattern: type === 'PATTERN' ? '^[0-9]{2}\\.[0-9]{2}-[0-9]{4}$' : null,
    filter: true,
    contextPrefix: i % 2 === 1,
    citationPosition: null,
    sortOrder: i * 10,
    deletionPending: false,
    values: generate(type === 'SELECT' ? values : 0, (v) => ({
      code: `wert_${v}`,
      label: scenarioName('Wert', v),
      retiring: v % 9 === 0,
    })),
  }
}

/** The library's own metadata fields under a scenario; null keeps the regular (none). */
export function scenarioMetadataFields(
  scenario: MockScenario,
): LibraryMetadataFieldResponse[] | null {
  switch (scenario) {
    case 'small':
      return [field(1, 0, 'PATTERN')]
    case 'scale':
      return [field(1, 0, 'PATTERN'), ...generate(4, (i) => field(i + 1, 40))]
    case 'empty':
      return []
    case 'failed':
    case 'partial':
    case 'slow':
      return [field(1, 0, 'PATTERN'), field(2, 6)]
    default:
      return null
  }
}

/**
 * The status of the run the `slow` scenario keeps going: 62 % of the library's own documents,
 * started by hand at 11:05 on the fixture's today, never done.
 */
export function runningStatus(libraryId: string, documents: number): IndexingStatusResponse {
  const total = Math.max(1, documents)
  const done = Math.round(total * 0.62)
  const skipped = Math.min(3, done)
  return {
    libraryId,
    status: 'RUNNING',
    documentCount: done - skipped,
    totalDocuments: total,
    documentsSkipped: skipped,
    documentsFailed: 0,
    documentsIndexedTotal: done - skipped,
    message: 'Indexing in progress',
    timestamp: berlin(24, 11, 12).toISOString(),
  }
}

/** Two scope entries of a library in the form its `unlistedScopeKeys` name them. */
export function unlistedKeysOf(library: LibraryResponse): string[] {
  const settings = (library.sourceSettings ?? {}) as Record<string, unknown>
  const list = <T>(key: string) => ((settings[key] as T[] | undefined) ?? []).slice(0, 2)
  switch (library.sourceType) {
    case 'CONFLUENCE':
      return list<{ key: string }>('spaces').map((space) => space.key)
    case 'SHAREPOINT':
      return list<{ driveId: string }>('libraries').map((entry) => `drive:${entry.driveId}`)
    case 'NEXTCLOUD':
    case 'SMB':
    case 'DROPBOX':
      return list<string>('folders')
    case 'GOOGLE_DRIVE':
      return list<{ drive?: string; folder?: string }>('scopes').map((scope) =>
        scope.drive ? `drive:${scope.drive}` : `folder:${scope.folder ?? ''}`,
      )
    case 'S3':
      return list<{ bucket: string; prefix?: string | null }>('scopes').map(
        (scope) => `${scope.bucket}/${scope.prefix ?? ''}`,
      )
    default:
      return []
  }
}
