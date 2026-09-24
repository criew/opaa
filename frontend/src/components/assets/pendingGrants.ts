import type { AssetRole, AssetType, PermissionSubjectType } from '../../types/api'
import { upsertAssetGrant } from '../../services/api'
import { notify } from '../../stores/notificationStore'
import { assetTypeLabel } from '../../utils/labels'

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

/**
 * Applies the noted grants of a just created asset and names the refused ones in a warning that
 * outlives the navigation to the detail page. The wizard always leaves after this: the asset
 * exists, and staying on the last step would offer to create it a second time.
 */
export async function applyPendingGrantsAfterCreation(
  assetType: AssetType,
  assetId: string,
  grants: PendingGrant[],
): Promise<void> {
  const failed = await applyPendingGrants(assetType, assetId, grants)
  if (failed.length === 0) return
  const noun = assetTypeLabel(assetType)
  notify(
    `Die ${noun} wurde angelegt, aber diese Freigaben konnten nicht gespeichert werden: ${failed.join(', ')}. Ergänzen Sie sie im Reiter „Freigaben“ unter „Berechtigungen“.`,
    'warning',
  )
}
