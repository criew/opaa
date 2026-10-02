import type { AssetType } from '../../types/api'

/** One chosen asset - enough to submit it and to name it in a summary. */
export interface AssetPick {
  assetType: AssetType
  assetId: string
  name: string
}

/** What "In Space verwenden" hands the space wizard when it starts a new space with an asset. */
export interface SpaceCreateLocationState {
  preselect?: AssetPick
}

export function assetPickKey(pick: { assetType: AssetType | string; assetId: string }): string {
  return `${pick.assetType}:${pick.assetId}`
}
