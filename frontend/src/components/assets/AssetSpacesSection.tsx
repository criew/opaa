import PageSection from '../PageSection'
import AssetSpacesList from './AssetSpacesList'
import type { AssetType } from '../../types/api'

/**
 * „Zuordnungen" (#1941): die Spaces, in denen das Asset als Datenquelle bereitsteht. Für eine
 * lesende Rolle ist der Abschnitt eine Auskunft; lösen darf die Zuordnung, wer das Asset verwaltet.
 */
export default function AssetSpacesSection({
  assetType,
  assetId,
  canManage,
}: {
  assetType: AssetType
  assetId: string
  canManage: boolean
}) {
  return (
    <PageSection
      title="Zuordnungen"
      description="Die Spaces, in denen dieses Objekt als Datenquelle bereitsteht."
    >
      <AssetSpacesList
        key={`${assetType}-${assetId}`}
        assetType={assetType}
        assetId={assetId}
        canManage={canManage}
      />
    </PageSection>
  )
}
