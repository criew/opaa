import { useState } from 'react'
import DetailSection, { TextAction } from '@/shared/ui/DetailSection'
import AssetSpacesList from './AssetSpacesList'
import { UseInSpaceDialog } from './UseInSpaceButton'
import type { AssetType } from '@/shared/api/types'

/**
 * The content of the tab „Zuordnungen": the spaces the asset stands in, „In Space verwenden" at
 * the section's right edge like every adding action of the page.
 */
export default function AssetSpacesSection({
  assetType,
  assetId,
  name,
  canManage,
  mayUseInSpace,
  refreshToken = 0,
  onChanged,
}: {
  assetType: AssetType
  assetId: string
  name: string
  canManage: boolean
  mayUseInSpace: boolean
  /** Changes when an association was made elsewhere on the page, e.g. from the head's „⋯". */
  refreshToken?: number
  /** Called after an association was created or detached here. */
  onChanged?: () => void
}) {
  // A new association reloads the list, so it shows up without a page reload.
  const [version, setVersion] = useState(0)
  const [dialogOpen, setDialogOpen] = useState(false)
  return (
    <DetailSection
      title="Spaces"
      action={
        mayUseInSpace ? (
          <TextAction onClick={() => setDialogOpen(true)}>In Space verwenden</TextAction>
        ) : undefined
      }
    >
      {mayUseInSpace && (
        <UseInSpaceDialog
          assetType={assetType}
          assetId={assetId}
          name={name}
          open={dialogOpen}
          onClose={() => setDialogOpen(false)}
          onAssociated={() => {
            setVersion((v) => v + 1)
            onChanged?.()
          }}
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
