import type { AssetType } from '../../types/api'
import type { AssetTileData } from './assetTileData'

/** One chosen asset - enough to submit it and to name it in a summary or on a tile. */
export interface AssetPick {
  assetType: AssetType
  assetId: string
  name: string
  description?: string | null
  /** What its tile showed when it was chosen; without it the tile shows name and type only. */
  tile?: AssetTileData
}

/** What "In Space verwenden" hands the space wizard when it starts a new space with an asset. */
export interface SpaceCreateLocationState {
  preselect?: AssetPick
}

export function assetPickKey(pick: { assetType: AssetType | string; assetId: string }): string {
  return `${pick.assetType}:${pick.assetId}`
}
