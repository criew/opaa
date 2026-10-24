import type { SourceBrowseResponse } from '@/shared/api/types'
import { browseSource } from '../../api/libraryApi'
import { normalizeS3Prefix, validateS3Scope, type S3ScopeInput } from '../../utils/s3Source'
import {
  googleDriveScopeFromKey,
  googleDriveScopeKey,
  googleDriveScopeLabel,
  type GoogleDriveScope,
} from '../../utils/googleDriveSource'
import {
  SHAREPOINT_API,
  sharePointFolderLabel,
  sharePointFolderPath,
  sharePointLibraryLabel,
  sharePointLibraryName,
  type SharePointLibraryChoice,
} from '../../utils/sharePointSource'
import type { ConfluenceSpaceRef } from '../../utils/confluenceSource'
import type { ConnectionFields } from '../sources/sourceConnection'
import type { BrowseResult, Crumb, SelectionItem, SelectionSource } from './selection'

type Probe = Pick<ConnectionFields, 'probeRequest'>

function resultOf(
  response: SourceBrowseResponse,
  map: (key: string, name: string) => BrowseResult['entries'][number],
): BrowseResult {
  return {
    entries: response.entries.map((entry) => map(entry.key, entry.name?.trim() || entry.key)),
    complete: response.complete,
    message: response.message ?? null,
  }
}

// ---- folder paths (Nextcloud, SMB, Dropbox) -----------------------------------------------------

function lastSegment(path: string) {
  const segments = path.split('/').filter(Boolean)
  return segments[segments.length - 1] ?? path
}

/** A folder path as a chosen object: its parent folders are its trail. */
export function folderItem(path: string): SelectionItem {
  const segments = path.split('/').filter(Boolean)
  const trail: Crumb[] = segments.slice(0, -1).map((name, i) => ({
    key: `/${segments.slice(0, i + 1).join('/')}`,
    name,
  }))
  return { key: path, name: lastSegment(path), trail }
}

/** The folders of a one-per-line field as chosen objects; „/“ (everything) as none. */
export function folderItemsOf(lines: string): SelectionItem[] {
  return lines
    .split('\n')
    .map((line) => line.trim())
    .filter((line, i, all) => line !== '' && line !== '/' && all.indexOf(line) === i)
    .map(folderItem)
}

/** Chosen folders back as the one-per-line field; none means „/“. */
export function foldersOfItems(items: SelectionItem[]): string {
  return items.length === 0 ? '/' : items.map((item) => item.key).join('\n')
}

export function folderSelectionSource(
  type: 'NEXTCLOUD' | 'SMB' | 'DROPBOX',
  rootLabel: string,
  probe: Probe,
  request: () => { sourceUrl: string; sourceCredentials?: string; query?: Record<string, unknown> },
): SelectionSource {
  return {
    rootLabel,
    noun: ['Ordner', 'Ordner'],
    nested: true,
    max: 50,
    serverSearch: () => false,
    searchLabel: (level) =>
      `In „${level.length ? level[level.length - 1].name : rootLabel}“ suchen`,
    list: async (level) => {
      const base = request()
      const path = level.length ? level[level.length - 1].key : '/'
      const response = await browseSource(
        type,
        probe.probeRequest({ ...base, query: { ...base.query, path } }),
      )
      return resultOf(response, (key, name) => ({
        key,
        name: name.includes('/') ? lastSegment(name) : name,
        selectable: true,
        openable: true,
        // the stored settings separate folders by comma
        ...(key.includes(',') ? { blocked: 'Ordner mit Komma im Pfad sind nicht wählbar.' } : {}),
      }))
    },
    manual: {
      label: 'Ordnerpfad',
      placeholder: '/Projekte/2026',
      kind: 'add',
      resolve: async (text) => {
        const path = `/${text.trim().replace(/^\/+|\/+$/g, '')}`
        if (path === '/') return 'Bitte einen Ordner unterhalb von / angeben.'
        if (path.includes(',')) return 'Ein Ordnerpfad darf kein Komma enthalten.'
        return folderItem(path)
      },
    },
  }
}

// ---- S3 buckets and prefixes --------------------------------------------------------------------

/** A scope as a chosen object: the bucket alone, or bucket/prefix below its bucket and parent prefixes. */
export function s3Item(scope: S3ScopeInput): SelectionItem {
  const bucket = scope.bucket.trim()
  const prefix = normalizeS3Prefix(scope.prefix)
  if (!prefix) return { key: bucket, name: bucket, trail: [] }
  const segments = prefix.split('/').filter(Boolean)
  const trail: Crumb[] = [
    { key: bucket, name: bucket },
    ...segments.slice(0, -1).map((name, i) => ({
      key: `${bucket}/${segments.slice(0, i + 1).join('/')}/`,
      name,
    })),
  ]
  return { key: `${bucket}/${prefix}`, name: segments[segments.length - 1], trail }
}

export function s3ScopeOfItem(item: SelectionItem): S3ScopeInput {
  const slash = item.key.indexOf('/')
  return slash < 0
    ? { bucket: item.key, prefix: '' }
    : { bucket: item.key.slice(0, slash), prefix: item.key.slice(slash + 1) }
}

export function s3SelectionSource(
  probe: Probe,
  request: () => { sourceUrl: string; sourceCredentials?: string; query: Record<string, unknown> },
): SelectionSource {
  return {
    rootLabel: 'Buckets',
    levelNoun: (level) => (level.length === 0 ? 'Buckets' : 'Präfixe'),
    noun: ['Bucket oder Präfix', 'Buckets und Präfixe'],
    nested: true,
    max: 50,
    serverSearch: () => false,
    searchLabel: (level) =>
      level.length ? `In „${level[level.length - 1].name}“ suchen` : 'Buckets suchen',
    list: async (level) => {
      const base = request()
      if (level.length === 0) {
        const response = await browseSource('S3', probe.probeRequest(base))
        return resultOf(response, (key) => ({ key, name: key, selectable: true, openable: true }))
      }
      const bucket = level[0].key
      const prefix = level.length > 1 ? level[level.length - 1].key.slice(bucket.length + 1) : ''
      const response = await browseSource(
        'S3',
        probe.probeRequest({ ...base, query: { ...base.query, bucket, prefix } }),
      )
      return resultOf(response, (key, name) => ({
        key: `${bucket}/${key}`,
        name: name.replace(/\/$/, '').split('/').pop() ?? name,
        selectable: true,
        openable: true,
      }))
    },
    manual: {
      label: 'Bucket/Präfix',
      placeholder: 'protokolle/2026/',
      kind: 'add',
      resolve: async (text) => {
        const [bucket, ...rest] = text.trim().replace(/^\/+/, '').split('/')
        const scope = { bucket, prefix: rest.join('/') }
        return validateS3Scope(scope) ?? s3Item(scope)
      },
    },
  }
}

// ---- Google Drive areas -------------------------------------------------------------------------

export function driveItem(scope: GoogleDriveScope): SelectionItem {
  return { key: googleDriveScopeKey(scope), name: googleDriveScopeLabel(scope), trail: [] }
}

export function driveScopeOfItem(item: SelectionItem): GoogleDriveScope | null {
  return googleDriveScopeFromKey(item.key, item.name)
}

export function driveSelectionSource(
  probe: Probe,
  request: () => { sourceUrl: string; sourceCredentials?: string; query: Record<string, unknown> },
): SelectionSource {
  return {
    rootLabel: 'Google Drive',
    noun: ['Geteilte Ablage oder Ordner', 'Geteilte Ablagen und Ordner'],
    nested: false,
    max: 50,
    serverSearch: () => false,
    searchLabel: () => 'Geteilte Ablagen und Ordner suchen',
    list: async () => {
      const response = await browseSource('GOOGLE_DRIVE', probe.probeRequest(request()))
      return resultOf(response, (key, name) => ({ key, name, selectable: true, openable: false }))
    },
    manual: {
      label: 'Ordner-ID',
      placeholder: '1AbCdEfGhIjKlMnOp',
      kind: 'add',
      resolve: async (text) => {
        const scope = googleDriveScopeFromKey(`folder:${text.trim()}`, null)
        return scope ? driveItem(scope) : 'Eine Ordner-ID besteht aus Buchstaben, Ziffern, - und _.'
      },
    },
  }
}

// ---- Confluence spaces --------------------------------------------------------------------------

export function spaceItem(space: ConfluenceSpaceRef): SelectionItem {
  return { key: space.key, name: space.name || space.key, trail: [] }
}

export function spaceOfItem(item: SelectionItem): ConfluenceSpaceRef {
  return { key: item.key, name: item.name === item.key ? null : item.name }
}

export function spaceSelectionSource(list: () => Promise<SourceBrowseResponse>): SelectionSource {
  return {
    rootLabel: 'Spaces',
    noun: ['Space', 'Spaces'],
    nested: false,
    max: 500,
    serverSearch: () => false,
    searchLabel: () => 'Spaces suchen',
    list: async () =>
      resultOf(await list(), (key, name) => ({
        key,
        name,
        selectable: true,
        openable: false,
      })),
  }
}

// ---- SharePoint sites, document libraries, folders ----------------------------------------------

/** The chosen document libraries and folders as objects of the dialog. */
export function sharePointItemsOf(libraries: SharePointLibraryChoice[]): SelectionItem[] {
  return libraries.flatMap((library): SelectionItem[] => {
    const site: Crumb[] = library.site
      ? [{ key: `site:${library.site.id}`, name: library.site.name ?? library.site.id }]
      : []
    const drive = { key: `drive:${library.driveId}`, name: sharePointLibraryLabel(library) }
    if (library.folders.length === 0) return [{ ...drive, trail: site }]
    return library.folders.map((folder) => ({
      key: `folder:${folder.id}`,
      name: sharePointFolderLabel(folder),
      trail: [...site, drive],
    }))
  })
}

/** The site every chosen entry lies in, as the level the dialog opens at; none when they differ. */
export function sharePointCommonSite(items: SelectionItem[]): Crumb[] | undefined {
  const sites = new Set(items.map((item) => item.trail[0]?.key ?? ''))
  const [first] = items
  return sites.size === 1 && first?.trail[0]?.key.startsWith('site:') ? [first.trail[0]] : undefined
}

/** Chosen objects back as document libraries narrowed to folders. */
export function sharePointLibrariesOfItems(items: SelectionItem[]): SharePointLibraryChoice[] {
  const byDrive = new Map<string, SharePointLibraryChoice>()
  for (const item of items) {
    const driveCrumbAt = item.key.startsWith('drive:')
      ? -1
      : item.trail.findIndex((crumb) => crumb.key.startsWith('drive:'))
    const drive = driveCrumbAt < 0 ? item : item.trail[driveCrumbAt]
    if (!drive.key.startsWith('drive:')) continue
    const site = item.trail.find((crumb) => crumb.key.startsWith('site:'))
    const driveId = drive.key.slice('drive:'.length)
    const named = site && !drive.name.endsWith(`(${site.name})`)
    const choice = byDrive.get(driveId) ?? {
      driveId,
      name: named ? sharePointLibraryName(drive.name, site.name) : drive.name,
      folders: [],
      site: site ? { id: site.key.slice('site:'.length), name: site.name } : null,
    }
    if (item.key.startsWith('folder:')) {
      const between = item.trail.slice(driveCrumbAt + 1).map((crumb) => crumb.name)
      choice.folders.push({
        id: item.key.slice('folder:'.length),
        name: between.length ? sharePointFolderPath(between, item.name) : item.name,
      })
    }
    byDrive.set(driveId, choice)
  }
  return [...byDrive.values()]
}

export function sharePointSelectionSource(probe: Probe): SelectionSource {
  const browse = (query: Record<string, unknown>) =>
    browseSource('SHAREPOINT', probe.probeRequest({ sourceUrl: SHAREPOINT_API, query }))
  return {
    rootLabel: 'Sites',
    levelNoun: (level) =>
      level.length === 0 ? 'Sites' : level.length === 1 ? 'Dokumentbibliotheken' : 'Ordner',
    noun: ['Dokumentbibliothek oder Ordner', 'Dokumentbibliotheken und Ordner'],
    nested: true,
    max: 50,
    serverSearch: (level) => level.length === 0,
    searchLabel: (level) =>
      level.length === 0 ? 'Sites suchen' : `In „${level[level.length - 1].name}“ suchen`,
    list: async (level, search) => {
      const last = level[level.length - 1]
      if (!last) {
        const response = await browse({ search })
        return resultOf(response, (key, name) => ({ key, name, selectable: false, openable: true }))
      }
      if (last.key.startsWith('site:')) {
        const response = await browse({ site: last.key.slice('site:'.length) })
        return resultOf(response, (key, name) => ({ key, name, selectable: true, openable: true }))
      }
      const drive = level.find((crumb) => crumb.key.startsWith('drive:'))!
      const response = await browse({
        drive: drive.key.slice('drive:'.length),
        ...(last.key.startsWith('folder:') ? { folder: last.key.slice('folder:'.length) } : {}),
      })
      return resultOf(response, (key, name) => ({ key, name, selectable: true, openable: true }))
    },
    manual: {
      label: 'Adresse der Site',
      placeholder: 'https://contoso.sharepoint.com/sites/rathaus',
      kind: 'open',
      resolve: async (text) => {
        if (!/^https:\/\//i.test(text.trim())) return 'Die Adresse beginnt mit https://.'
        try {
          const response = await browse({ siteUrl: text.trim() })
          const site = response.entries[0]
          if (!site) return response.message ?? 'Unter dieser Adresse liegt keine Site.'
          return { key: site.key, name: site.name?.trim() || site.key, trail: [] }
        } catch (err) {
          return err instanceof Error ? err.message : 'Die Site ließ sich nicht öffnen.'
        }
      },
    },
  }
}
