import type { LibraryResponse } from '@/shared/api/types'
import { displayPath } from './setup/paths'
import {
  googleDriveScopeKey,
  googleDriveScopesOf,
  type GoogleDriveSettings,
} from '../utils/googleDriveSource'

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
      return keys.map((key) => spaces.find((space) => space.key === key)?.name?.trim() || key)
    }
    case 'SHAREPOINT': {
      const libraries = list<{ driveId: string; name?: string }>(settings, 'libraries')
      return keys.map((key) => {
        const id = key.replace(/^drive:/, '')
        return (
          libraries.find((entry) => entry.driveId === id)?.name?.trim() || 'unbekannte Bibliothek'
        )
      })
    }
    // one OneDrive per library: its key names the drive, the Umfang the OneDrive
    case 'ONEDRIVE':
      return keys.map(() => 'OneDrive')
    case 'GOOGLE_DRIVE': {
      const scopes = googleDriveScopesOf(settings as GoogleDriveSettings)
      return keys.map((key) => {
        const name = scopes.find((scope) => googleDriveScopeKey(scope) === key)?.name?.trim()
        if (name) return name
        if (key === 'myDrive') return 'Meine Ablage'
        return key.startsWith('drive:') ? 'unbekannte Ablage' : 'unbekannter Ordner'
      })
    }
    case 'NEXTCLOUD':
    case 'SMB':
    case 'DROPBOX':
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
