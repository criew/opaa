import { HttpResponse } from 'msw'
import type { SourceBrowseResponse } from '../types/api'
import {
  mockSharePointSites,
  type MockSharePointDrive,
  type MockSharePointFolder,
} from './libraryFixtures'

/** The wording of SharePointSourceConnector and SharePointFileStore, so the mock answers alike. */
export const SHAREPOINT_MESSAGES = {
  noProfile: 'Für die Auflistung ist ein Zugang mit App-Registrierung erforderlich',
  noStage: 'Bitte eine Site suchen oder ihre Adresse angeben.',
  searchNeedsReadAll:
    'Die Suche nach Sites braucht die Berechtigung Sites.Read.All. Bitte die Adresse der Site angeben.',
  notFound:
    'Microsoft Graph kennt die Site oder Bibliothek nicht oder zeigt sie der Anwendung nicht.',
  forbidden:
    'Die Anwendung darf das nicht lesen. Bei der Berechtigung Sites.Selected muss die Site für die App freigegeben sein.',
  invalidSiteUrl:
    'Die Adresse der Site ist eine https-Adresse ohne Port, Anmeldedaten, Abfrage und Anker, etwa https://contoso.sharepoint.com/sites/team',
  notALibrary:
    'Das Laufwerk ist keine SharePoint-Dokumentbibliothek; OneDrive und andere Laufwerke werden nicht gelesen.',
  libraryNotVisible:
    'Die Dokumentbibliothek ist für die Anwendung nicht sichtbar. Bei der Berechtigung Sites.Selected muss die Site für die App freigegeben sein.',
  testNeedsProfile: 'Bitte einen Zugang mit App-Registrierung wählen.',
  testNeedsLibrary: 'Bitte mindestens eine Dokumentbibliothek wählen.',
} as const

interface ProbeBody {
  connectionProfileId?: string | null
  libraryId?: string | null
}

/** Whether the request goes through a profile: SharePoint signs in through nothing else. */
function throughProfile(body: ProbeBody) {
  return Boolean(body.connectionProfileId || body.libraryId)
}

function incomplete(message: string) {
  return HttpResponse.json({ complete: false, entries: [], message } satisfies SourceBrowseResponse)
}

function listing(entries: SourceBrowseResponse['entries']) {
  return HttpResponse.json({
    complete: true,
    entries,
    message: null,
  } satisfies SourceBrowseResponse)
}

/** The drive behind `driveId` with the site holding it. */
function driveOf(driveId: string) {
  for (const site of mockSharePointSites) {
    const drive = site.drives.find((d) => d.id === driveId)
    if (drive) return { site, drive }
  }
  return null
}

function findFolder(folders: MockSharePointFolder[], id: string): MockSharePointFolder | null {
  for (const folder of folders) {
    if (folder.id === id) return folder
    const below = findFolder(folder.children, id)
    if (below) return below
  }
  return null
}

/** `sites/{host}:/{path}` as SharePointSourceConnector#sitePath reads it; null for a refused one. */
function siteAddress(raw: string): string | null {
  let url: URL
  try {
    url = new URL(raw.trim())
  } catch {
    return null
  }
  if (url.protocol !== 'https:' || url.search || url.hash || url.username || url.port) return null
  const path = url.pathname.replace(/\/+$/, '')
  if (path.includes('..') || path.includes(':')) return null
  return `${url.hostname.toLowerCase()}${path}`
}

/**
 * POST /source-types/SHAREPOINT/browse in stages, like the connector: `search` (refused, the mock
 * app holds Sites.Selected), `siteUrl`, `site`, `drive` with an optional `folder`. A refusal by
 * Graph is a listing that is not complete, with the connector's reason.
 */
export function browseSharePoint(body: ProbeBody & { query?: Record<string, unknown> | null }) {
  if (!throughProfile(body)) {
    return HttpResponse.json({ error: SHAREPOINT_MESSAGES.noProfile }, { status: 400 })
  }
  const query = body.query ?? {}
  if (typeof query.drive === 'string') {
    const found = driveOf(query.drive)
    if (!found) return incomplete(SHAREPOINT_MESSAGES.notFound)
    if (!found.site.granted) return incomplete(SHAREPOINT_MESSAGES.forbidden)
    let level: MockSharePointFolder[] = found.drive.folders
    if (typeof query.folder === 'string') {
      const folder = findFolder(found.drive.folders, query.folder)
      if (!folder) return incomplete(SHAREPOINT_MESSAGES.notFound)
      level = folder.children
    }
    return listing(level.map((folder) => ({ key: `folder:${folder.id}`, name: folder.name })))
  }
  if (typeof query.site === 'string') {
    const site = mockSharePointSites.find((s) => s.id === query.site)
    if (!site) return incomplete(SHAREPOINT_MESSAGES.notFound)
    if (!site.granted) return incomplete(SHAREPOINT_MESSAGES.forbidden)
    return listing(
      site.drives
        .filter((drive: MockSharePointDrive) => drive.driveType === 'documentLibrary')
        .map((drive) => ({ key: `drive:${drive.id}`, name: drive.name })),
    )
  }
  if (typeof query.siteUrl === 'string') {
    const address = siteAddress(query.siteUrl)
    if (!address) {
      return HttpResponse.json({ error: SHAREPOINT_MESSAGES.invalidSiteUrl }, { status: 400 })
    }
    const site = mockSharePointSites.find((s) => siteAddress(s.url) === address)
    if (!site) return incomplete(SHAREPOINT_MESSAGES.notFound)
    if (!site.granted) return incomplete(SHAREPOINT_MESSAGES.forbidden)
    return listing([{ key: `site:${site.id}`, name: site.name }])
  }
  if (typeof query.search === 'string') {
    return incomplete(SHAREPOINT_MESSAGES.searchNeedsReadAll)
  }
  return incomplete(SHAREPOINT_MESSAGES.noStage)
}

interface SharePointLibrarySetting {
  driveId?: string
  folders?: Array<string | { id?: string; name?: string | null }>
}

/** The summary of SharePointSourceConnector#testConnection, singular for a single library. */
function testMessage(reachable: number, total: number) {
  if (total === 1) {
    return reachable === 1
      ? 'Anmeldung erfolgreich; die Dokumentbibliothek ist erreichbar.'
      : 'Die Dokumentbibliothek ist für die Anwendung nicht erreichbar.'
  }
  const unreachable = total - reachable
  return unreachable === 0
    ? `Anmeldung erfolgreich; alle ${total} Dokumentbibliotheken sind erreichbar.`
    : `${unreachable} von ${total} Dokumentbibliotheken ${unreachable === 1 ? 'ist' : 'sind'} für die Anwendung nicht erreichbar.`
}

/**
 * POST /libraries/source-test for SHAREPOINT, like SharePointSourceConnector#testConnection: each
 * library checked - released, a document library, its folders present - with the finding per
 * library in `details.libraries`.
 */
export function testSharePoint(
  body: ProbeBody & { sourceSettings?: Record<string, unknown> | null },
) {
  if (!throughProfile(body)) {
    return HttpResponse.json({
      reachable: false,
      credentialsVerified: false,
      message: SHAREPOINT_MESSAGES.testNeedsProfile,
    })
  }
  const libraries = (body.sourceSettings?.libraries ?? []) as SharePointLibrarySetting[]
  if (libraries.length === 0) {
    return HttpResponse.json({
      reachable: false,
      credentialsVerified: false,
      message: SHAREPOINT_MESSAGES.testNeedsLibrary,
    })
  }
  const findings = libraries.map((library) => {
    const driveId = library.driveId ?? ''
    const found = driveOf(driveId)
    if (!found || !found.site.granted) {
      return { driveId, reachable: false, message: SHAREPOINT_MESSAGES.libraryNotVisible }
    }
    if (found.drive.driveType !== 'documentLibrary') {
      return { driveId, reachable: false, message: SHAREPOINT_MESSAGES.notALibrary }
    }
    for (const folder of library.folders ?? []) {
      const id = typeof folder === 'string' ? folder : (folder.id ?? '')
      const shown = typeof folder === 'string' ? id : folder.name || id
      if (!findFolder(found.drive.folders, id)) {
        return {
          driveId,
          reachable: false,
          message: `Der gewählte Ordner „${shown}“ ist in der Dokumentbibliothek nicht mehr vorhanden; bitte die Ordnerauswahl anpassen.`,
        }
      }
    }
    return { driveId, reachable: true }
  })
  const reachable = findings.filter((finding) => finding.reachable).length
  const total = findings.length
  return HttpResponse.json({
    reachable: reachable === total,
    credentialsVerified: true,
    message: testMessage(reachable, total),
    details: { libraries: findings },
  })
}
