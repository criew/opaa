import { useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import AssetSpacesList from './AssetSpacesList'
import UseInSpaceButton from './UseInSpaceButton'
import type { AssetType } from '../../types/api'

/**
 * The content of the tab „Zuordnungen": „In Space verwenden" above the spaces the asset
 * stands in. Without a heading of its own - the tab already names it.
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
  return (
    <Box component="section" aria-label="Zuordnungen">
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 2,
          flexWrap: 'wrap',
          mb: 2,
        }}
      >
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', maxWidth: '80ch' }}>
          Die Spaces, in denen dieses Objekt als Datenquelle bereitsteht.
        </Typography>
        {mayUseInSpace && (
          <UseInSpaceButton
            assetType={assetType}
            assetId={assetId}
            name={name}
            size="small"
            onAssociated={() => {
              setVersion((v) => v + 1)
              onChanged?.()
            }}
          />
        )}
      </Box>
      <AssetSpacesList
        key={`${assetType}-${assetId}-${refreshToken}-${version}`}
        assetType={assetType}
        assetId={assetId}
        canManage={canManage}
        onChanged={onChanged}
      />
    </Box>
  )
}
