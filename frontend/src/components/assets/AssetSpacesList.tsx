import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import type { AssetSpaceAssociationListResponse, AssetType } from '../../types/api'
import { detachSpaceAsset, getAssetSpaceAssociations } from '../../services/api'
import { confirmAction } from '../../stores/confirmStore'
import { assetTypeLabel } from '../../utils/labels'

interface AssetSpacesListProps {
  assetType: AssetType
  assetId: string
  /**
   * Whether the caller may manage the asset. Only then does the endpoint answer with the reader
   * circle of each space, and only then may an association be detached. Without it the list stays
   * read-only - a missing value must never grow an affordance (#1939).
   */
  canManage: boolean
}

/**
 * The "Zuordnungen" list: the spaces the asset is associated with. A manager sees every
 * association, unfiltered by their own space membership, and may detach each one unilaterally
 * (docs/features/spaces-and-assets.md#assets-in-einen-space-assoziieren). A plain reader sees the
 * names of the spaces they may know of and, as a number alone, how many further ones there are.
 */
export default function AssetSpacesList({ assetType, assetId, canManage }: AssetSpacesListProps) {
  const [links, setLinks] = useState<AssetSpaceAssociationListResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const noun = assetTypeLabel(assetType)

  useEffect(() => {
    let cancelled = false
    getAssetSpaceAssociations(assetType, assetId)
      .then((data) => {
        if (!cancelled) setLinks(data)
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Laden fehlgeschlagen')
      })
    return () => {
      cancelled = true
    }
  }, [assetType, assetId])

  async function handleDetach(spaceId: string, spaceName: string) {
    const confirmed = await confirmAction({
      question: `Bereitstellung im Space "${spaceName}" lösen?`,
      confirmLabel: 'Lösen',
      tone: 'neutral',
    })
    if (!confirmed) return
    setError(null)
    try {
      await detachSpaceAsset(spaceId, assetId)
      setLinks((prev) =>
        prev ? { ...prev, items: prev.items.filter((a) => a.spaceId !== spaceId) } : null,
      )
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Lösen fehlgeschlagen')
    }
  }

  const hiddenCount = links?.hiddenCount ?? 0
  const hiddenNote =
    hiddenCount === 1
      ? '+ 1 weiterer Space, den Sie nicht sehen können'
      : `+ ${hiddenCount} weitere Spaces, die Sie nicht sehen können`

  return (
    <Box>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {links === null ? (
        // Loading without a layout jump (guidelines 5.7) - one skeleton row where the first
        // association will land.
        <Skeleton variant="rounded" height={32} sx={{ maxWidth: 360 }} />
      ) : links.items.length === 0 && hiddenCount === 0 ? (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Diese {noun} ist derzeit keinem Space zugeordnet. Die Zuordnung erfolgt in den
          Einstellungen des jeweiligen Space.
        </Typography>
      ) : (
        <Stack spacing={1}>
          {links.items.map((association) => (
            <Box
              key={association.spaceId}
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 1.5,
                flexWrap: 'wrap',
              }}
            >
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                <Typography>{association.spaceName}</Typography>
                {association.narrowerReaderCircle && (
                  <Tooltip
                    title={`Mindestens ein Mitglied dieses Space hat keinen eigenen Lesezugriff auf diese ${noun}.`}
                  >
                    <Chip label="nicht alle Mitglieder lesen" size="small" color="warning" />
                  </Tooltip>
                )}
              </Stack>
              {canManage && (
                <Button
                  color="error"
                  size="small"
                  onClick={() => void handleDetach(association.spaceId, association.spaceName)}
                >
                  Lösen
                </Button>
              )}
            </Box>
          ))}
          {/* Die Zahl steht bewusst da: Sie sagt, dass die Liste unvollständig ist, ohne einen
              einzigen privaten Space zu benennen. */}
          {hiddenCount > 0 && (
            <Typography variant="body2" sx={{ color: 'text.secondary' }}>
              {hiddenNote}
            </Typography>
          )}
        </Stack>
      )}
    </Box>
  )
}
