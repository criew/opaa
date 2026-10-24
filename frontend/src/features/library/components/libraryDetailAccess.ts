import type { AssetRole } from '@/shared/api/types'

export function canEditLibrary(role: AssetRole | undefined): boolean {
  return role === 'MANAGER' || role === 'OWNER'
}

export function canDeleteLibrary(role: AssetRole | undefined): boolean {
  return role === 'OWNER'
}

// ADR-0018, Entscheidung 2: whoever is at least EDITOR on the library may trigger it - the same
// threshold as for uploading and deleting documents.
export function canManageDocuments(role: AssetRole | undefined): boolean {
  return role === 'EDITOR' || role === 'MANAGER' || role === 'OWNER'
}

export function formatIndexedAt(indexedAt: string | null | undefined): string {
  if (!indexedAt) return '—'
  return new Date(indexedAt).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
}

/** The page's areas; the active one is shareable via the "tab" search param (URL as state). */
export type LibraryDetailTab =
  'dokumente' | 'verlauf' | 'quelle' | 'metadaten' | 'freigaben' | 'zuordnungen'

/**
 * The area named by `?tab=`. Every role sees every area, so only the source area can be missing -
 * an UPLOAD library has no source - and the metadata area, while the installation has switched
 * metadata off (ADR-0044). An unknown or retired value (such as
 * „indizierung" and „verwaltung") falls back to the documents.
 */
export function resolveTab(
  requested: string | null,
  hasSourceTab: boolean,
  hasMetadataTab: boolean,
  hasRunsTab = false,
): LibraryDetailTab {
  switch (requested) {
    case 'verlauf':
      return hasRunsTab ? 'verlauf' : 'dokumente'
    case 'quelle':
      return hasSourceTab ? 'quelle' : 'dokumente'
    case 'metadaten':
      return hasMetadataTab ? 'metadaten' : 'dokumente'
    case 'freigaben':
      return 'freigaben'
    case 'zuordnungen':
      return 'zuordnungen'
    default:
      return 'dokumente'
  }
}
