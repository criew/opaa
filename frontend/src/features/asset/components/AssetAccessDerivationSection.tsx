import { useEffect, useRef, useState } from 'react'
import type { AssetType } from '@/shared/api/types'
import { useAuthStore } from '@/shared/session/authStore'
import { TextAction } from '@/shared/ui/DetailSection'
import ValueRow from '@/shared/ui/ValueRow'
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
    <ValueRow
      title="Herleitung"
      value={`Ihr Weg zu dieser ${assetTypeLabel(assetType)}`}
      action={
        <TextAction
          onClick={() => {
            if (shown) returnFocus.current = true
            setShown((v) => !v)
          }}
          aria-expanded={shown}
          context="Herleitung"
        >
          {shown ? 'Schließen' : 'Anzeigen'}
        </TextAction>
      }
    >
      {shown && (
        <AccessDerivation target={{ kind: 'asset', assetType, assetId }} subjectName={ownName} />
      )}
    </ValueRow>
  )
}
