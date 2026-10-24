import type { SourceTypeKey, SourceBrowseResponse } from '@/shared/api/types'
import { browseSource } from '../../api/libraryApi'
import type { PickedItem, PickerCrumb, PickerLevel } from './pickerTypes'

/** A folder path („/a/b") as a chosen item whose trail lists its parent folders. */
export function pickedOfPath(path: string, separator = '/'): PickedItem {
  const segments = path.split(separator).filter(Boolean)
  const trail: PickerCrumb[] = segments.slice(0, -1).map((name, index) => ({
    key: `/${segments.slice(0, index + 1).join('/')}`,
    name,
  }))
  return { key: path, name: segments[segments.length - 1] ?? path, trail }
}

/** The chosen folders of a newline-separated folder list; „/" (everything) chooses none. */
export function pickedOfFolders(folders: string[]): PickedItem[] {
  return folders.filter((folder) => folder !== '/').map((folder) => pickedOfPath(folder))
}

/** The newline-separated folder list of a selection; none chosen reads the whole source. */
export function foldersOfPicked(items: PickedItem[]): string {
  return items.length === 0 ? '/' : items.map((item) => item.key).join('\n')
}

export function levelOf(response: SourceBrowseResponse, hasChildren: boolean): PickerLevel {
  return {
    complete: response.complete,
    message: response.message ?? null,
    entries: response.entries.map((entry) => ({
      key: entry.key,
      name: entry.name ?? entry.key,
      selectable: true,
      hasChildren,
    })),
  }
}

/** Lists the direct subfolders of the trail's last folder (the root without one). */
export function folderLoader(
  sourceType: SourceTypeKey,
  request: (query: Record<string, unknown>) => Parameters<typeof browseSource>[1],
) {
  return async (trail: PickerCrumb[]): Promise<PickerLevel> => {
    const path = trail.length ? trail[trail.length - 1].key : '/'
    const response = await browseSource(sourceType, request({ path }))
    return levelOf(response, true)
  }
}
