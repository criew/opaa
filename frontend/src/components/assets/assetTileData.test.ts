import { describe, expect, it } from 'vitest'
import type { CatalogEntryResponse } from '../../types/api'
import { tileFromCatalogEntry } from './assetTileData'

function libraryEntry(sourceLockNotice: string | null): CatalogEntryResponse {
  return {
    assetType: 'KNOWLEDGE_LIBRARY',
    assetId: 'library-1',
    name: 'Wiki',
    ownerType: 'USER',
    ownerId: 'user-1',
    origin: 'LOCAL',
    visibility: 'RESTRICTED',
    myRole: 'OWNER',
    status: 'READY',
    updatedAt: '2026-10-01T09:00:00Z',
    knowledgeLibrary: { sourceType: 'CONFLUENCE', indexingStatus: 'READY', sourceLockNotice },
    itemCount: 3,
    spaceCount: 0,
  } as CatalogEntryResponse
}

describe('tileFromCatalogEntry', () => {
  // Spezifikation „Konnektor-Freigabe und Sperre“: die Bibliotheksliste trägt den Sperrhinweis.
  it('carries the lock of a library as a line of the tile', () => {
    expect(tileFromCatalogEntry(libraryEntry('Gesperrt – Inhalt wird nicht mehr …')).note).toBe(
      'Gesperrt – Inhalt wird nicht mehr aktualisiert',
    )
    expect(tileFromCatalogEntry(libraryEntry(null)).note).toBeNull()
  })
})
