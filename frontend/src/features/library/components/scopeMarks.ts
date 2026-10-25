import type { LibraryResponse } from '@/shared/api/types'

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
  library: Pick<LibraryResponse, 'sourceType' | 'sourceSettings'>,
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
    case 'NEXTCLOUD':
    case 'SMB':
    case 'DROPBOX':
      return keys.map(folderName)
    default:
      return keys
  }
}
