import type { AssetType, CatalogEntryResponse, CatalogEntryStatus } from '../../types/api'
import { assetTypeDefinition } from './assetTypeRegistry'

/** A state that needs attention; READY carries none. */
export type AssetTileStatus = Exclude<CatalogEntryStatus, 'READY'>

/**
 * What one asset tile shows. Only type, id and name are always known; a source that knows less
 * than the catalog (an association, the token choice) leaves the rest out and the tile omits it.
 */
export interface AssetTileData {
  assetType: AssetType
  assetId: string
  name: string
  description?: string | null
  /** Released to all accounts: the tile carries the globe beside its type badge. */
  isPublic?: boolean
  /** The caller's own favorite mark; absent where the source does not know it. */
  favorite?: boolean
  /** The figures line, e.g. "12 Dokumente · in 2 Spaces". */
  figures?: string | null
  /** Who to turn to, with whether that is a group. */
  responsible?: { label: string; group: boolean } | null
  /** Shown as "Aktualisiert am …" while nothing needs attention. */
  updatedAt?: string | null
  /** The type's own state when it needs attention. */
  status?: AssetTileStatus | null
  /** An open succession, a line of its own. */
  successionOpen?: boolean
  /** A further line the source adds, e.g. the end of a release. */
  note?: string | null
}

/** The spread in words, e.g. "in 3 Spaces". */
export function spreadLabel(spaceCount: number): string {
  if (spaceCount === 0) return 'in keinem Space'
  return spaceCount === 1 ? 'in 1 Space' : `in ${spaceCount} Spaces`
}

/**
 * The type's own state - for a knowledge library its indexing, which stays visible while the
 * succession is open; every other type has no measure of its own and is READY.
 */
function ownStatus(entry: CatalogEntryResponse): CatalogEntryStatus {
  if (entry.knowledgeLibrary) return entry.knowledgeLibrary.indexingStatus
  return entry.status === 'SUCCESSION_OPEN' ? 'READY' : entry.status
}

/**
 * Who to turn to: while the succession is open that is its addressee, otherwise the owner. A name
 * the caller may not see stays unnamed (ADR-0036, Entscheidung 9).
 */
export function responsibleParty(source: {
  ownerType: CatalogEntryResponse['ownerType']
  ownerLabel?: string | null
  succession?: CatalogEntryResponse['succession']
}): { label: string; group: boolean } {
  if (source.succession) return { label: source.succession.addresseeLabel, group: true }
  const group = source.ownerType === 'GROUP'
  return { label: source.ownerLabel ?? (group ? 'eine Gruppe' : 'eine Person'), group }
}

/** The tile of a catalog entry - the fullest a tile gets. */
export function tileFromCatalogEntry(entry: CatalogEntryResponse): AssetTileData {
  const status = ownStatus(entry)
  const definition = assetTypeDefinition(entry.assetType)
  return {
    assetType: entry.assetType,
    assetId: entry.assetId,
    name: entry.name,
    description: entry.description ?? null,
    isPublic: entry.visibility === 'PUBLIC',
    favorite: entry.favorite,
    figures: definition
      ? `${definition.extentLabel(entry.itemCount)} · ${spreadLabel(entry.spaceCount)}`
      : null,
    responsible: responsibleParty(entry),
    // An upload library has no runs, so no lastIndexedAt; its date is then its last change.
    updatedAt: entry.knowledgeLibrary?.lastIndexedAt ?? entry.updatedAt ?? null,
    status: status === 'READY' ? null : status,
    successionOpen: Boolean(entry.succession),
    // the short form of the notice; the library's source tab carries the whole of it
    note: entry.knowledgeLibrary?.sourceLockNotice
      ? 'Gesperrt – Inhalt wird nicht mehr aktualisiert'
      : null,
  }
}
