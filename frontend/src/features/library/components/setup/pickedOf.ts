import type { ConfluenceSourceValues } from '../../utils/confluenceSource'
import {
  googleDriveScopeKey,
  googleDriveScopeLabel,
  type GoogleDriveScope,
} from '../../utils/googleDriveSource'
import { normalizeS3Prefix, type S3ScopeInput } from '../../utils/s3Source'
import {
  sharePointFolderLabel,
  sharePointLibraryLabel,
  type SharePointLibraryChoice,
} from '../../utils/sharePointSource'
import type { PickedItem, PickerCrumb } from './pickerTypes'

/*
 * The stored selection of each source type as chosen items of the split selection and back:
 * the right column, the review step and the Reiter „Quelle" read them alike.
 */

/** The chosen spaces as items of the selection. */
export function pickedOfSpaces(spaces: ConfluenceSourceValues['spaces']): PickedItem[] {
  return spaces.map((space) => ({ key: space.key, name: space.name ?? space.key, trail: [] }))
}

export function pickedOfDriveScopes(scopes: GoogleDriveScope[]): PickedItem[] {
  return scopes.map((scope) => ({
    key: googleDriveScopeKey(scope),
    name: googleDriveScopeLabel(scope),
    trail: [],
    raw: { kind: scope.kind, id: scope.id },
  }))
}

/** A scope as a chosen item: the bucket, or a prefix inside it with its parent prefixes as trail. */
export function pickedOfScope(scope: S3ScopeInput): PickedItem {
  const bucket = scope.bucket.trim()
  const prefix = normalizeS3Prefix(scope.prefix)
  const segments = prefix.split('/').filter(Boolean)
  if (segments.length === 0)
    return { key: bucket, name: bucket, trail: [], raw: { bucket, prefix: '' } }
  const trail: PickerCrumb[] = [
    { key: bucket, name: bucket, raw: { bucket } },
    ...segments.slice(0, -1).map((name, index) => ({
      key: `${bucket}/${segments.slice(0, index + 1).join('/')}/`,
      name,
      raw: { bucket, prefix: `${segments.slice(0, index + 1).join('/')}/` },
    })),
  ]
  return {
    key: `${bucket}/${prefix}`,
    name: segments[segments.length - 1],
    trail,
    raw: { bucket, prefix },
  }
}

export function pickedOfScopes(scopes: S3ScopeInput[]): PickedItem[] {
  return scopes.filter((scope) => scope.bucket.trim() !== '').map(pickedOfScope)
}

/** „Dokumente (Rathaus)" split into the library's and the site's name. */
export function splitLibraryName(name: string): { library: string; site: string | null } {
  const match = /^(.*) \(([^()]+)\)$/.exec(name)
  return match ? { library: match[1], site: match[2] } : { library: name, site: null }
}

/** The chosen libraries and folders as items with site and library in their trail. */
export function pickedOfLibraries(libraries: SharePointLibraryChoice[]): PickedItem[] {
  return libraries.flatMap((library): PickedItem[] => {
    const { library: name, site } = splitLibraryName(sharePointLibraryLabel(library))
    const siteCrumbs: PickerCrumb[] = site ? [{ key: `site-name:${site}`, name: site }] : []
    const driveCrumb: PickerCrumb = {
      key: `drive:${library.driveId}`,
      name,
      raw: { driveId: library.driveId },
    }
    if (library.folders.length === 0) {
      return [
        {
          key: driveCrumb.key,
          name,
          trail: siteCrumbs,
          raw: { driveId: library.driveId, libraryName: library.name },
        },
      ]
    }
    return library.folders.map((folder) => {
      const segments = sharePointFolderLabel(folder).split(' / ')
      return {
        key: `folder:${folder.id}`,
        name: segments[segments.length - 1],
        trail: [
          ...siteCrumbs,
          driveCrumb,
          ...segments.slice(0, -1).map((segment, index) => ({
            key: `folder-name:${library.driveId}:${segments.slice(0, index + 1).join('/')}`,
            name: segment,
          })),
        ],
        raw: {
          driveId: library.driveId,
          libraryName: library.name,
          folderId: folder.id,
          path: folder.name,
        },
      }
    })
  })
}

/** The selection back as libraries, each whole or with its chosen folders. */
export function librariesOfPicked(items: PickedItem[]): SharePointLibraryChoice[] {
  const byDrive = new Map<string, SharePointLibraryChoice>()
  for (const item of items) {
    const driveId = String(item.raw?.driveId ?? '')
    if (!driveId) continue
    const library = byDrive.get(driveId) ?? {
      driveId,
      name: (item.raw?.libraryName as string | null) ?? null,
      folders: [],
    }
    if (item.raw?.folderId) {
      library.folders.push({
        id: String(item.raw.folderId),
        name: String(item.raw.path ?? item.name),
      })
    }
    byDrive.set(driveId, library)
  }
  return [...byDrive.values()]
}
