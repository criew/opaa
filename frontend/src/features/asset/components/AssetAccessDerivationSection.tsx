import { useEffect, useRef, useState } from 'react'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import type { AssetType } from '@/shared/api/types'
import { useAuthStore } from '@/shared/session/authStore'
import TabSection from '@/shared/ui/TabSection'
import AccessDerivation from './AccessDerivation'
import { assetTypeLabel } from '../../../shared/assetTypes'

/**
 * ADR-0036, Entscheidung 9: flat and shown as flat - every person sees their own way to this asset,
 * whatever their role. Asked for on demand: the derivation costs a request of its own.
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
    <TabSection
      title={`Warum sehe ich diese ${assetTypeLabel(assetType)}?`}
      action={
        shown ? undefined : (
          <Button ref={openButton} size="small" onClick={() => setShown(true)}>
            Herleitung anzeigen
          </Button>
        )
      }
    >
      {shown && (
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
      )}
    </TabSection>
  )
}
