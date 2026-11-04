import { useState } from 'react'
import DetailSection, { TextAction } from '@/shared/ui/DetailSection'
import AssetSpacesList from './AssetSpacesList'
import { UseInSpaceInline } from './UseInSpaceButton'
import type { AssetType } from '@/shared/api/types'

/**
 * The content of the tab „Zuordnungen": the spaces the asset stands in, with „In Space verwenden"
 * at the section's right edge. Like „Freigeben" the action opens its form inline below the head,
 * not in a dialog (ADR-0039, Entscheidung 4).
 */
export default function AssetSpacesSection({
  assetType,
  assetId,
  name,
  canManage,
  mayUseInSpace,
  reachFrozen,
  refreshToken = 0,
  onChanged,
}: {
  assetType: AssetType
  assetId: string
  name: string
  canManage: boolean
  mayUseInSpace: boolean
  /** An open succession freezes the reach: „In Space verwenden" offers nothing to choose. */
  reachFrozen?: boolean
  /** Changes when an association was made elsewhere on the page. */
  refreshToken?: number
  /** Called after an association was created or detached here. */
  onChanged?: () => void
}) {
  // A new association reloads the list, so it shows up without a page reload.
  const [version, setVersion] = useState(0)
  const [showForm, setShowForm] = useState(false)
  return (
    <DetailSection
      title="Spaces"
      action={
        mayUseInSpace && !showForm ? (
          <TextAction onClick={() => setShowForm(true)}>In Space verwenden</TextAction>
        ) : undefined
      }
    >
      {mayUseInSpace && showForm && (
        <UseInSpaceInline
          assetType={assetType}
          assetId={assetId}
          name={name}
          reachFrozen={reachFrozen}
          onAssociated={() => {
            setVersion((v) => v + 1)
            onChanged?.()
          }}
          onDone={() => setShowForm(false)}
        />
      )}
      <AssetSpacesList
        key={`${assetType}-${assetId}-${refreshToken}-${version}`}
        assetType={assetType}
        assetId={assetId}
        canManage={canManage}
        onChanged={onChanged}
      />
    </DetailSection>
  )
}
