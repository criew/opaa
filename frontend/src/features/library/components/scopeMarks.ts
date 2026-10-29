import type { LibraryResponse } from '@/shared/api/types'
import { displayPath } from './setup/paths'

type Settings = Record<string, unknown>

function list<T>(settings: Settings, key: string): T[] {
  return (settings[key] as T[] | undefined) ?? []
}

/** The last segment of a folder path, the way the Umfang lists it. */
function folderName(path: string): string {
  const parts = path.split('/').filter(Boolean)
  return parts.length ? parts[parts.length - 1] : path
}

/**
 * The names of the scope entries the last listing could not read, resolved from the keys the
 * backend reports (`unlistedScopeKeys`) against the library's own selection - never a raw id.
 */
export function unreadableScopeNames(
  library: Pick<LibraryResponse, 'sourceType' | 'sourceSettings'> &
    Partial<Pick<LibraryResponse, 'sourcePath' | 'sourceUrl'>>,
  keys: string[],
): string[] {
  const settings = (library.sourceSettings ?? {}) as Settings
  switch (library.sourceType) {
    case 'CONFLUENCE': {
      const spaces = list<{ key: string; name?: string | null }>(settings, 'spaces')
      return keys.map((key) => spaces.find((space) => space.key === key)?.name ?? key)
    }
    case 'SHAREPOINT': {
      const libraries = list<{ driveId: string; name?: string }>(settings, 'libraries')
      return keys.map((key) => {
        const id = key.replace(/^drive:/, '')
        return libraries.find((entry) => entry.driveId === id)?.name ?? 'unbekannte Bibliothek'
      })
    }
    // one OneDrive per library: its key names the drive, the Umfang the OneDrive
    case 'ONEDRIVE':
      return keys.map(() => 'OneDrive')
    case 'GOOGLE_DRIVE': {
      const scopes = list<{ drive?: string; folder?: string; name?: string }>(settings, 'scopes')
      return keys.map((key) => {
        const [kind, id] = key.split(':', 2)
        const scope = scopes.find((entry) =>
          kind === 'drive' ? entry.drive === id : entry.folder === id,
        )
        return scope?.name ?? (kind === 'drive' ? 'unbekannte Ablage' : 'unbekannter Ordner')
      })
    }
    // under a team app the keys are the namespace ids of the team folders
    case 'DROPBOX': {
      const teamFolders = list<{ id: string; name?: string | null }>(settings, 'teamFolders')
      if (teamFolders.length === 0) return keys.map(folderName)
      return keys.map(
        (key) => teamFolders.find((folder) => folder.id === key)?.name || `Team-Ordner ${key}`,
      )
    }
    case 'NEXTCLOUD':
    case 'SMB':
      return keys.map(folderName)
    // a directory or web address is one entry: its unreadable parts are named below it
    case 'FILESYSTEM':
    case 'HTTP_DIRECTORY': {
      const root =
        (library.sourceType === 'FILESYSTEM' ? library.sourcePath : library.sourceUrl) ?? ''
      return keys.map((key) => displayPath(key.startsWith(root) ? key.slice(root.length) : key))
    }
    default:
      return keys
  }
}
