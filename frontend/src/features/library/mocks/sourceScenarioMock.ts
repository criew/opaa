import { HttpResponse } from 'msw'
import type { SourceBrowseResponse, SourceTypeKey } from '@/shared/api/types'
import {
  currentMockScenario,
  generate,
  scenarioErrorBody,
  scenarioName,
  type MockScenario,
} from '@/shared/mocks/mockScenario'
import { SHAREPOINT_MESSAGES } from './sharePointSourceMock'

/**
 * Listings and connection tests of the source types under the fixture scenarios of
 * `@/shared/mocks/mockScenario`. Generated trees are lazy: a folder key encodes its position, so
 * 200 folders per level and eight levels never exist as objects. `default` and `slow` answer from
 * the regular fixtures in `libraryHandlers` and return null here.
 */

type Entry = SourceBrowseResponse['entries'][number]

interface ScenarioCounts {
  sites: number
  libraries: number
  /** Folders per level, for SharePoint, Nextcloud, SMB, Dropbox and S3 prefixes. */
  folders: number
  depth: number
  spaces: number
  areas: number
  buckets: number
  complete: boolean
}

const COUNTS: Partial<Record<MockScenario, ScenarioCounts>> = {
  small: {
    sites: 1,
    libraries: 1,
    folders: 2,
    depth: 2,
    spaces: 2,
    areas: 1,
    buckets: 1,
    complete: true,
  },
  scale: {
    sites: 17,
    libraries: 25,
    folders: 200,
    depth: 8,
    spaces: 450,
    areas: 300,
    buckets: 600,
    complete: true,
  },
  empty: {
    sites: 0,
    libraries: 0,
    folders: 0,
    depth: 0,
    spaces: 0,
    areas: 0,
    buckets: 0,
    complete: true,
  },
  partial: {
    sites: 6,
    libraries: 8,
    folders: 30,
    depth: 4,
    spaces: 40,
    areas: 25,
    buckets: 30,
    complete: false,
  },
}

/** The counts of a generating scenario; null for `default`, `slow` and `failed`. */
export function scenarioCounts(scenario: MockScenario = currentMockScenario()) {
  return COUNTS[scenario] ?? null
}

/** Why a listing of the `partial` scenario is incomplete, in the connectors' wording. */
const INCOMPLETE_MESSAGES: Partial<Record<SourceTypeKey, string>> = {
  SHAREPOINT:
    'Microsoft Graph hat die Auflistung nach 10 Seiten beendet; es werden nur die ersten Einträge angezeigt.',
  CONFLUENCE:
    'Confluence hat die Auflistung begrenzt (HTTP 429, zu viele Anfragen); es fehlen Spaces. Bitte später erneut laden.',
  GOOGLE_DRIVE:
    'Google Drive hat nicht alle geteilten Ablagen und Ordner geliefert; die Liste ist unvollständig.',
  S3: 'Die Bucket-Liste ist unvollständig: Der Schlüssel darf nicht alle Buckets auflisten.',
  NEXTCLOUD: 'Einige Ordner konnten nicht gelesen werden (HTTP 403); die Liste ist unvollständig.',
  SMB: 'Einige Ordner der Freigabe sind für das Dienstkonto gesperrt (STATUS_ACCESS_DENIED); die Liste ist unvollständig.',
  DROPBOX: 'Dropbox hat die Auflistung nach 2.000 Einträgen beendet; die Liste ist unvollständig.',
}

/** The refused listing of the `failed` scenario: status and the connector's German message. */
const BROWSE_FAILURES: Partial<Record<SourceTypeKey, [number, string]>> = {
  SHAREPOINT: [403, SHAREPOINT_MESSAGES.forbidden],
  CONFLUENCE: [
    502,
    'Confluence antwortet nicht: Zeitüberschreitung nach 30 Sekunden (HTTP 504 vom Server).',
  ],
  GOOGLE_DRIVE: [
    403,
    'Google Drive verweigert die Auflistung: Dem Dienstkonto fehlt die Berechtigung (HTTP 403).',
  ],
  S3: [
    403,
    'Der Objektspeicher verweigert die Bucket-Auflistung (AccessDenied). Geben Sie Bucket und Präfix von Hand an.',
  ],
  NEXTCLOUD: [502, 'Die Nextcloud antwortet nicht (HTTP 503 Service Unavailable).'],
  SMB: [
    502,
    'Die Dateifreigabe ist nicht erreichbar: Zeitüberschreitung beim Verbindungsaufbau zu Port 445.',
  ],
  DROPBOX: [502, 'Dropbox antwortet nicht (HTTP 503). Bitte später erneut versuchen.'],
}

/** The refused connection test of the `failed` scenario, per type. */
const TEST_FAILURES: Partial<Record<SourceTypeKey, string>> = {
  FILESYSTEM: 'Das Verzeichnis ist nicht lesbar: Zugriff verweigert.',
  CONFLUENCE:
    'Confluence hat die Zugangsdaten abgelehnt (HTTP 401). Prüfen Sie E-Mail-Adresse und API-Token.',
  S3: 'Der Objektspeicher hat die Anmeldung abgelehnt (SignatureDoesNotMatch): Access Key oder Secret Key ist falsch.',
  GOOGLE_DRIVE:
    'Google hat den Dienstkonto-Schlüssel abgelehnt (invalid_grant). Der Schlüssel ist widerrufen oder abgelaufen.',
  SHAREPOINT:
    'Microsoft Entra ID hat die Anmeldung der App abgelehnt (AADSTS7000222: Der geheime Clientschlüssel ist abgelaufen).',
  NEXTCLOUD: 'Nextcloud hat Benutzername oder App-Passwort abgelehnt (HTTP 401).',
  SMB: 'Die Freigabe hat die Anmeldung abgelehnt (STATUS_LOGON_FAILURE): Benutzername oder Passwort ist falsch.',
  DROPBOX:
    'Dropbox hat die Verbindung abgelehnt (invalid_access_token): Die Zustimmung ist abgelaufen. Bitte die Quelle neu verbinden.',
}

function listing(entries: Entry[], type: SourceTypeKey, counts: ScenarioCounts) {
  return HttpResponse.json({
    complete: counts.complete,
    entries,
    message: counts.complete ? null : (INCOMPLETE_MESSAGES[type] ?? null),
  } satisfies SourceBrowseResponse)
}

function failure(status: number, message: string) {
  return HttpResponse.json(scenarioErrorBody(status, message), { status })
}

// ---- path trees (Nextcloud, SMB, Dropbox, S3 prefixes) ------------------------------------------

const LEVEL_BASES = ['Ordner', 'Vorgang', 'Akte', 'Jahrgang', 'Teil', 'Band', 'Abschnitt', 'Anlage']

/** The direct children of `path` ("/" or "/a/b") in a tree of `perLevel` folders, `depth` deep. */
export function pathChildren(path: string, counts: ScenarioCounts, separator = '/'): Entry[] {
  const segments = path.split(separator).filter((segment) => segment !== '')
  if (segments.length >= counts.depth) return []
  const base = LEVEL_BASES[segments.length % LEVEL_BASES.length]
  const parent = segments.length === 0 ? '' : `/${segments.join('/')}`
  return generate(counts.folders, (i) => {
    const name = scenarioName(base, i)
    return { key: `${parent}/${name}`, name }
  })
}

/** S3 common prefixes below `prefix` ("" or "a/b/") of `bucket`, keys ending in a slash. */
function prefixChildren(prefix: string, counts: ScenarioCounts): Entry[] {
  const segments = prefix.split('/').filter((segment) => segment !== '')
  if (segments.length >= counts.depth) return []
  const base = LEVEL_BASES[segments.length % LEVEL_BASES.length].toLowerCase()
  return generate(counts.folders, (i) => {
    const name = `${base}-${String(i).padStart(3, '0')}${i % 3 === 0 ? '-eingliederungshilfe-und-teilhabe-archiv-2009-2019' : ''}`
    return { key: `${segments.map((s) => `${s}/`).join('')}${name}/`, name }
  })
}

// ---- SharePoint ---------------------------------------------------------------------------------

const SITE_BASES = ['Team-Site', 'Projekt', 'Amt', 'Fachbereich']

export function siteName(i: number) {
  return i === 1 ? 'Rathaus' : scenarioName(SITE_BASES[i % SITE_BASES.length], i)
}

function siteUrl(i: number) {
  return `https://rheinfurt.sharepoint.com/sites/${i === 1 ? 'rathaus' : `site-${String(i).padStart(3, '0')}`}`
}

/** `gen-site-<i>` / `gen-drive-<site>-<i>` / `<drive>_F1-4-2` (folder positions, 1-based; Graph-safe characters). */
function parseDrive(driveId: string) {
  const match = /^gen-drive-(\d+)-(\d+)$/.exec(driveId)
  return match ? { site: Number(match[1]), library: Number(match[2]) } : null
}

function sharePointFolders(driveId: string, folder: string | null, counts: ScenarioCounts) {
  const positions = folder ? folder.split('_F')[1]?.split('-').map(Number) : []
  if (positions === undefined || positions.length >= counts.depth) return []
  const base = LEVEL_BASES[positions.length % LEVEL_BASES.length]
  return generate(counts.folders, (i) => ({
    key: `folder:${driveId}_F${[...positions, i].join('-')}`,
    name: scenarioName(base, i),
  }))
}

function browseSharePoint(query: Record<string, unknown>, counts: ScenarioCounts): Response {
  const type = 'SHAREPOINT'
  if (typeof query.drive === 'string') {
    if (!parseDrive(query.drive)) return listing([], type, counts)
    const folder = typeof query.folder === 'string' ? query.folder : null
    return listing(sharePointFolders(query.drive, folder, counts), type, counts)
  }
  if (typeof query.site === 'string') {
    const site = Number(/^gen-site-(\d+)$/.exec(query.site)?.[1] ?? 0)
    if (!site || site > counts.sites) return listing([], type, counts)
    return listing(
      generate(counts.libraries, (i) => ({
        key: `drive:gen-drive-${site}-${i}`,
        name: i === 1 ? 'Dokumente' : scenarioName('Dokumentbibliothek', i),
      })),
      type,
      counts,
    )
  }
  const sites = generate(counts.sites, (i) => ({
    key: `site:gen-site-${i}`,
    name: siteName(i),
    url: siteUrl(i),
  }))
  if (typeof query.siteUrl === 'string') {
    const wanted = query.siteUrl.trim().replace(/\/+$/, '').toLowerCase()
    const site = sites.find((candidate) => candidate.url === wanted)
    if (!site) {
      return HttpResponse.json({
        complete: false,
        entries: [],
        message: SHAREPOINT_MESSAGES.notFound,
      } satisfies SourceBrowseResponse)
    }
    return listing([{ key: site.key, name: site.name }], type, counts)
  }
  const search = typeof query.search === 'string' ? query.search.trim().toLowerCase() : ''
  return listing(
    sites
      .filter((site) => site.name.toLowerCase().includes(search))
      .map(({ key, name }) => ({ key, name })),
    type,
    counts,
  )
}

// ---- listings -----------------------------------------------------------------------------------

const SPACE_BASES = ['Bauamt', 'Personal und Organisation', 'IT-Betrieb', 'Kämmerei', 'Rechtsamt']

function spaces(count: number): Entry[] {
  return generate(count, (i) =>
    i % 10 === 0
      ? {
          key: `~nutzer${i}`,
          name: `Persönlicher Space von Nutzerin ${String(i).padStart(3, '0')}`,
        }
      : { key: `SP${i}`, name: scenarioName(SPACE_BASES[i % SPACE_BASES.length], i) },
  )
}

function driveAreas(count: number): Entry[] {
  return generate(count, (i) =>
    i % 4 === 1
      ? { key: `drive:0AGen${i}`, name: scenarioName('Geteilte Ablage', i) }
      : { key: `folder:1Gen${i}`, name: scenarioName('Freigegebener Ordner', i) },
  )
}

function buckets(count: number): Entry[] {
  return generate(count, (i) => ({
    key: `bucket-${String(i).padStart(4, '0')}${i % 3 === 0 ? '-archiv-eingliederungshilfe-teilhabe-2009' : ''}`,
    name: null,
  }))
}

/**
 * The listing of `type` under the active scenario, or null to answer from the regular fixtures.
 * Path-based types read `query.path` (also `query.folder`); S3 lists prefixes with `query.bucket`
 * and an optional `query.prefix`.
 */
export function scenarioBrowse(
  type: SourceTypeKey,
  query: Record<string, unknown> | null | undefined,
): Response | null {
  const scenario = currentMockScenario()
  if (scenario === 'failed') {
    const refused = BROWSE_FAILURES[type]
    return refused ? failure(...refused) : null
  }
  const counts = scenarioCounts(scenario)
  if (!counts) return null
  const q = query ?? {}
  switch (type) {
    case 'SHAREPOINT':
      return browseSharePoint(q, counts)
    case 'CONFLUENCE':
      return listing(spaces(counts.spaces), type, counts)
    case 'GOOGLE_DRIVE':
      return listing(driveAreas(counts.areas), type, counts)
    case 'S3':
      if (typeof q.bucket === 'string') {
        return listing(prefixChildren(String(q.prefix ?? ''), counts), type, counts)
      }
      return listing(buckets(counts.buckets), type, counts)
    case 'NEXTCLOUD':
    case 'SMB':
    case 'DROPBOX':
      return listing(pathChildren(String(q.path ?? q.folder ?? '/'), counts), type, counts)
    default:
      return null
  }
}

// ---- connection tests ---------------------------------------------------------------------------

interface TestBody {
  sourceType: SourceTypeKey
  sourcePath?: string | null
  sourceUrl?: string | null
  sourceCredentials?: string | null
  sourceSettings?: Record<string, unknown> | null
}

/** Whether the scope at `index` of `total` fails in the `partial` scenario: every second one. */
function failsPartially(index: number, total: number) {
  return total === 1 || index % 2 === 1
}

function partialTest(body: TestBody): Response | null {
  const settings = body.sourceSettings ?? {}
  switch (body.sourceType) {
    case 'S3': {
      const scopes = (
        (settings.scopes ?? []) as Array<{ bucket: string; prefix?: string | null }>
      ).map((scope, index, all) => {
        const fails = failsPartially(index, all.length)
        return {
          bucket: scope.bucket,
          prefix: scope.prefix ?? '',
          bucketReachable: !fails || index % 4 !== 1,
          listAllowed: !fails,
          readAllowed: !fails,
          objectCount: fails ? 0 : 12,
          objectCountIsLowerBound: false,
          message: fails
            ? index % 4 === 1
              ? 'Bucket nicht gefunden (NoSuchBucket).'
              : 'Auflisten verweigert (AccessDenied auf ListObjectsV2).'
            : null,
        }
      })
      const failed = scopes.filter((scope) => scope.message).length
      return HttpResponse.json({
        reachable: failed === 0,
        credentialsVerified: true,
        documentCount: 12 * (scopes.length - failed),
        message: `Anmeldung erfolgreich; ${failed} von ${scopes.length} Bereichen nicht lesbar.`,
        details: { scopes },
      })
    }
    case 'GOOGLE_DRIVE': {
      const scopes = (
        (settings.scopes ?? []) as Array<{ drive?: string; folder?: string; myDrive?: boolean }>
      ).map((scope, index, all) => {
        const fails = failsPartially(index, all.length)
        return {
          scope: scope.drive
            ? `drive:${scope.drive}`
            : scope.folder
              ? `folder:${scope.folder}`
              : 'myDrive',
          reachable: !fails,
          ...(fails
            ? { message: 'Das Dienstkonto ist kein Mitglied dieser Ablage (HTTP 404).' }
            : {}),
        }
      })
      const failed = scopes.filter((scope) => !scope.reachable).length
      return HttpResponse.json({
        reachable: failed === 0,
        credentialsVerified: true,
        message: `Anmeldung erfolgreich; ${failed} von ${scopes.length} Bereichen nicht erreichbar.`,
        details: { scopes },
      })
    }
    case 'SHAREPOINT': {
      const libraries = ((settings.libraries ?? []) as Array<{ driveId?: string }>).map(
        (library, index, all) => {
          const fails = failsPartially(index, all.length)
          return {
            driveId: library.driveId ?? '',
            reachable: !fails,
            ...(fails ? { message: SHAREPOINT_MESSAGES.libraryNotVisible } : {}),
          }
        },
      )
      const failed = libraries.filter((library) => !library.reachable).length
      return HttpResponse.json({
        reachable: failed === 0,
        credentialsVerified: true,
        message: `${failed} von ${libraries.length} Dokumentbibliotheken ${failed === 1 ? 'ist' : 'sind'} für die Anwendung nicht erreichbar.`,
        details: { libraries },
      })
    }
    case 'NEXTCLOUD':
    case 'SMB':
    case 'DROPBOX': {
      const folders = ((settings.folders as string[] | undefined) ?? ['/']).map(
        (folder, index, all) => ({
          folder,
          reachable: !failsPartially(index, all.length),
        }),
      )
      const refused = folders.filter((folder) => !folder.reachable)
      return HttpResponse.json({
        reachable: refused.length === 0,
        credentialsVerified: true,
        documentCount: 8 * (folders.length - refused.length),
        message: `Anmeldung erfolgreich; ${refused.length} von ${folders.length} Ordnern nicht lesbar: ${refused
          .slice(0, 3)
          .map((folder) => folder.folder)
          .join(', ')}${refused.length > 3 ? ' …' : ''} (Zugriff verweigert).`,
        details: {
          folders: folders.map((folder) => ({
            ...folder,
            message: folder.reachable ? null : 'Zugriff verweigert.',
          })),
        },
      })
    }
    case 'CONFLUENCE': {
      if (!body.sourceCredentials) return null
      const chosen = ((settings.spaces ?? []) as Array<{ key: string }>).map((space) => space.key)
      const unreadable = chosen.filter((_, index) => failsPartially(index, chosen.length))
      return HttpResponse.json({
        reachable: true,
        credentialsVerified: true,
        details: {
          edition: /atlassian\.net/i.test(body.sourceUrl ?? '') ? 'CLOUD' : 'DATA_CENTER',
          unreadableSpaces: unreadable,
        },
        documentCount: 40,
        message:
          chosen.length === 0
            ? 'Zugangsdaten gültig; die Liste der lesbaren Spaces ist unvollständig (HTTP 429).'
            : `Zugangsdaten gültig; ${unreadable.length} der ${chosen.length} gewählten Spaces sind nicht lesbar: ${unreadable.slice(0, 3).join(', ')}.`,
      })
    }
    case 'FILESYSTEM':
      return HttpResponse.json({
        reachable: true,
        documentCount: 120,
        message:
          'Verzeichnis erreichbar; 3 Unterverzeichnisse sind nicht lesbar (Zugriff verweigert).',
      })
    default:
      return HttpResponse.json({
        reachable: true,
        documentCount: 5,
        message: 'Erreichbar; 4 von 9 Einträgen wurden abgewiesen (HTTP 403).',
      })
  }
}

function failedTest(body: TestBody): Response | null {
  // the Confluence edition is detected without credentials; only the sign-in itself fails
  if (body.sourceType === 'CONFLUENCE' && !body.sourceCredentials) return null
  if (body.sourceType === 'HTTP_DIRECTORY' || body.sourceType === 'RSS_FEED') {
    return failure(502, 'Der Server antwortet mit HTTP 503 (Service Unavailable).')
  }
  const message = TEST_FAILURES[body.sourceType]
  if (!message) return null
  return HttpResponse.json({
    reachable: false,
    credentialsVerified: false,
    message,
    ...(body.sourceType === 'CONFLUENCE'
      ? {
          details: {
            edition: /atlassian\.net/i.test(body.sourceUrl ?? '') ? 'CLOUD' : 'DATA_CENTER',
          },
        }
      : {}),
  })
}

/**
 * The connection test of the `failed` and `partial` scenarios, or null to answer from the regular
 * test (which counts what the request names, so `small` and `scale` need nothing extra).
 */
export function scenarioTest(body: TestBody): Response | null {
  const scenario = currentMockScenario()
  if (scenario === 'failed') return failedTest(body)
  if (scenario === 'partial') return partialTest(body)
  return null
}
