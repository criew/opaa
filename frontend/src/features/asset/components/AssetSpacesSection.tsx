import { useState } from 'react'
import Box from '@mui/material/Box'
import TabSection from '@/shared/ui/TabSection'
import AssetSpacesList from './AssetSpacesList'
import UseInSpaceButton from './UseInSpaceButton'
import type { AssetType } from '@/shared/api/types'

/**
 * The content of the tab „Zuordnungen": the spaces the asset stands in, „In Space verwenden" as a
 * text button at the right content edge. Without a heading of its own - the tab already names it.
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
  const [empty, setEmpty] = useState(false)
  const useInSpace = mayUseInSpace ? (
    <UseInSpaceButton
      assetType={assetType}
      assetId={assetId}
      name={name}
      size="small"
      quiet
      onAssociated={() => {
        setVersion((v) => v + 1)
        onChanged?.()
      }}
    />
  ) : undefined
  return (
    <Box component="section" aria-label="Zuordnungen">
      <TabSection
        // empty, the action stands in the sentence of the empty state instead
        action={empty ? undefined : useInSpace}
      >
        <AssetSpacesList
          key={`${assetType}-${assetId}-${refreshToken}-${version}`}
          assetType={assetType}
          assetId={assetId}
          canManage={canManage}
          onChanged={onChanged}
          emptyAction={useInSpace}
          onEmptyChange={setEmpty}
        />
      </TabSection>
    </Box>
  )
}
