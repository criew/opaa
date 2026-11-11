import type { WizardReviewEntry } from '@/shared/ui/wizard/WizardReview'
import type { AssetOwnerType, GroupListResponse } from '@/shared/api/types'
import type { PendingGrant } from './pendingGrants'

interface MasterDataAnswers {
  name: string
  description: string
  onChangeMasterData: () => void
}

interface SharingAnswers {
  ownerType: AssetOwnerType
  selectedGroup: GroupListResponse | null
  pendingGrants: PendingGrant[]
  onChangeSharing: () => void
}

/** The rows „Name“ and „Beschreibung“ of the step „Prüfen“ in every asset wizard. */
export function masterDataReview({
  name,
  description,
  onChangeMasterData,
}: MasterDataAnswers): WizardReviewEntry[] {
  return [
    { label: 'Name', value: name.trim(), onChange: onChangeMasterData },
    { label: 'Beschreibung', value: description.trim(), onChange: onChangeMasterData },
  ]
}

/** The rows „Eigentum“ and „Freigaben“ of the step „Prüfen“ in every asset wizard. */
export function sharingReview({
  ownerType,
  selectedGroup,
  pendingGrants,
  onChangeSharing,
}: SharingAnswers): WizardReviewEntry[] {
  return [
    {
      label: 'Eigentum',
      value: ownerType === 'GROUP' ? `Gruppe ${selectedGroup?.name ?? ''}` : 'Mein Konto',
      onChange: onChangeSharing,
    },
    {
      label: 'Freigaben',
      value:
        pendingGrants.length === 0
          ? 'keine'
          : pendingGrants.length === 1
            ? '1 vorgemerkt'
            : `${pendingGrants.length} vorgemerkt`,
      onChange: onChangeSharing,
    },
  ]
}

/** The step „Prüfen“ of an asset wizard without a source: master data, then sharing. */
export function assetCreationReview(
  answers: MasterDataAnswers & SharingAnswers,
): WizardReviewEntry[] {
  return [...masterDataReview(answers), ...sharingReview(answers)]
}
