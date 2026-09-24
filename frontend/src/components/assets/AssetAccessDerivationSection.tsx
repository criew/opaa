import { useState } from 'react'
import Button from '@mui/material/Button'
import type { AssetType } from '../../types/api'
import PageSection from '../PageSection'
import AccessDerivation from '../permissions/AccessDerivation'
import { assetTypeLabel } from '../../utils/labels'

/**
 * ADR-0036, Entscheidung 9: flat and shown as flat - every person sees their own way to this asset,
 * whatever their role (#1939). Asked for on demand: the derivation costs a request of its own.
 */
export default function AssetAccessDerivationSection({
  assetType,
  assetId,
}: {
  assetType: AssetType
  assetId: string
}) {
  const [shown, setShown] = useState(false)

  return (
    <PageSection
      title={`Warum sehe ich diese ${assetTypeLabel(assetType)}?`}
      description="Ihr eigener Weg zur wirksamen Rolle. Ohne Vollmacht, ohne Protokoll."
    >
      {shown ? (
        <AccessDerivation target={{ kind: 'asset', assetType, assetId }} />
      ) : (
        <Button size="small" onClick={() => setShown(true)}>
          Herleitung anzeigen
        </Button>
      )}
    </PageSection>
  )
}
