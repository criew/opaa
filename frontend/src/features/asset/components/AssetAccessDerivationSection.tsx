import { useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { AssetType } from '@/shared/api/types'
import { useAuthStore } from '@/shared/session/authStore'
import DetailSection, { TextAction } from '@/shared/ui/DetailSection'
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
    <DetailSection title="Herleitung">
      {/* the same row as every other section of the tab: a line, a statement, its action */}
      <Box
        sx={{
          borderTop: 1,
          borderColor: 'divider',
          pt: 1,
          display: 'flex',
          alignItems: 'baseline',
          justifyContent: 'space-between',
          gap: 2,
        }}
      >
        <Typography sx={{ fontSize: 13.5 }}>
          Ihr Weg zu dieser {assetTypeLabel(assetType)}
        </Typography>
        <TextAction
          onClick={() => {
            if (shown) returnFocus.current = true
            setShown((v) => !v)
          }}
          aria-expanded={shown}
        >
          {shown ? 'Schließen' : 'Anzeigen'}
        </TextAction>
      </Box>
      {shown && (
        <Box sx={{ mt: 1 }}>
          <AccessDerivation target={{ kind: 'asset', assetType, assetId }} subjectName={ownName} />
        </Box>
      )}
    </DetailSection>
  )
}
