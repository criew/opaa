import type { AssetRole, AssetType, PermissionSubjectType } from '../../types/api'
import { upsertAssetGrant } from '../../services/api'

/** A grant noted in a creation wizard, applied once the asset exists. */
export interface PendingGrant {
  subjectType: PermissionSubjectType
  subjectId: string
  label: string
  role: AssetRole
}

/**
 * Applies the noted grants one after another through the asset-shell grant endpoint. A refused
 * grant does not stop the others; the labels of the refused ones come back so the wizard can name
 * them - the asset itself exists either way.
 */
export async function applyPendingGrants(
  assetType: AssetType,
  assetId: string,
  grants: PendingGrant[],
): Promise<string[]> {
  const failed: string[] = []
  for (const grant of grants) {
    try {
      await upsertAssetGrant(assetType, assetId, {
        subjectType: grant.subjectType,
        subjectId: grant.subjectId,
        role: grant.role,
      })
    } catch {
      failed.push(grant.label)
    }
  }
  return failed
}
