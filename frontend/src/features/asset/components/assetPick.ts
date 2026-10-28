import type { AssetType } from '@/shared/api/types'

/** One chosen asset - enough to submit it and to name it in a summary or on a tile. */
export interface AssetPick {
  assetType: AssetType
  assetId: string
  name: string
  description?: string | null
}

/** The one tile a change of the choice came from. */
export interface AssetPickToggle {
  pick: AssetPick
  /** The asset's reach is frozen by an open succession: no new association of it can succeed. */
  reachFrozen: boolean
}

/** What "In Space verwenden" hands the space wizard when it starts a new space with an asset. */
export interface SpaceCreateLocationState {
  preselect?: AssetPick
}

export function assetPickKey(pick: { assetType: AssetType | string; assetId: string }): string {
  return `${pick.assetType}:${pick.assetId}`
}
