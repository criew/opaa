import { useEffect, useRef, useState } from 'react'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import type { AssetType } from '../../types/api'
import { useAuthStore } from '../../stores/authStore'
import PageSection from '../PageSection'
import AccessDerivation from '../permissions/AccessDerivation'
import { assetTypeLabel } from './assetTypeRegistry'

/**
 * ADR-0036, Entscheidung 9: flat and shown as flat - every person sees their own way to this asset,
 * whatever their role (#1939). Asked for on demand: the derivation costs a request of its own.
 * Closing hands the focus back to the button that opened it.
 */
export default function AssetAccessDerivationSection({
  assetType,
  assetId,
}: {
  assetType: AssetType
  assetId: string
}) {
  const [shown, setShown] = useState(false)
  const openButton = useRef<HTMLButtonElement | null>(null)
  const ownName = useAuthStore((s) => s.user?.displayName ?? null)
  const returnFocus = useRef(false)

  useEffect(() => {
    if (shown || !returnFocus.current) return
    returnFocus.current = false
    openButton.current?.focus()
  }, [shown])

  return (
    <PageSection
      title={`Warum sehe ich diese ${assetTypeLabel(assetType)}?`}
      description="Ihr eigener Weg zur wirksamen Rolle. Ohne Vollmacht, ohne Protokoll."
    >
      {shown ? (
        <Stack spacing={0.5}>
          <AccessDerivation target={{ kind: 'asset', assetType, assetId }} subjectName={ownName} />
          <Link
            component="button"
            type="button"
            sx={{ alignSelf: 'flex-end', fontSize: 12 }}
            onClick={() => {
              returnFocus.current = true
              setShown(false)
            }}
          >
            Schließen
          </Link>
        </Stack>
      ) : (
        <Button ref={openButton} size="small" onClick={() => setShown(true)}>
          Herleitung anzeigen
        </Button>
      )}
    </PageSection>
  )
}
