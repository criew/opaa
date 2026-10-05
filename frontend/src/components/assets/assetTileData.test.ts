import { describe, expect, it } from 'vitest'
import type { CatalogEntryResponse, SourceBlock } from '../../types/api'
import { tileFromCatalogEntry } from './assetTileData'

function libraryEntry(sourceBlock: SourceBlock | null): CatalogEntryResponse {
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
    knowledgeLibrary: { sourceType: 'CONFLUENCE', indexingStatus: 'READY', sourceBlock },
    itemCount: 3,
    spaceCount: 0,
  } as CatalogEntryResponse
}

describe('tileFromCatalogEntry', () => {
  // Spezifikation „Konnektor-Freigabe und Sperre“: die Bibliotheksliste trägt den Sperrhinweis.
  it('carries the lock of a library as a line of the tile, naming who is in charge', () => {
    expect(
      tileFromCatalogEntry(
        libraryEntry({
          reason: 'TYPE_LOCKED',
          responsible: 'Systemverwaltung',
          notice: 'Gesperrt – Inhalt wird nicht mehr …',
        }),
      ).note,
    ).toBe('Gesperrt – Inhalt wird nicht mehr aktualisiert (zuständig: Systemverwaltung)')
    expect(
      tileFromCatalogEntry(
        libraryEntry({
          reason: 'PROFILE_REQUIRED',
          responsible: 'Verwaltende der Bibliothek',
          notice: 'Gesperrt – Inhalt wird nicht mehr …',
        }),
      ).note,
    ).toBe('Gesperrt – Inhalt wird nicht mehr aktualisiert (zuständig: Verwaltende der Bibliothek)')
    expect(tileFromCatalogEntry(libraryEntry(null)).note).toBeNull()
  })

  it('names the new reasons of a connected account in the short form of the tile', () => {
    const note = (reason: SourceBlock['reason'], responsible: string) =>
      tileFromCatalogEntry(libraryEntry({ reason, responsible, notice: '…' })).note
    expect(note('EXPIRED', 'Besitzerin der Bibliothek')).toBe(
      'Anmeldung abgelaufen – Inhalt wird nicht mehr aktualisiert (zuständig: Besitzerin der Bibliothek)',
    )
    expect(note('DORMANT', 'Besitzerin der Bibliothek bzw. Systemverwaltung')).toBe(
      'Ruhend – Inhalt wird nicht aktualisiert (zuständig: Besitzerin der Bibliothek bzw. Systemverwaltung)',
    )
    expect(note('OWNER_DEACTIVATED', 'Systemverwaltung')).toBe(
      'Konto deaktiviert – Inhalt wird nach Ablauf der Löschfrist gelöscht (zuständig: Systemverwaltung)',
    )
    expect(
      tileFromCatalogEntry(
        libraryEntry({
          reason: 'OWNER_DEACTIVATED',
          responsible: 'Systemverwaltung',
          notice: '…',
          contentDeletedOn: '2026-11-03',
        }),
      ).note,
    ).toBe(
      'Konto deaktiviert – Inhalt wird ab dem 03.11.2026 gelöscht (zuständig: Systemverwaltung)',
    )
  })
})
