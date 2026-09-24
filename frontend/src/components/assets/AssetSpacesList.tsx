import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import type { AssetSpaceAssociationResponse, AssetType } from '../../types/api'
import { detachSpaceAsset, getAssetSpaceAssociations } from '../../services/api'
import { confirmAction } from '../../stores/confirmStore'
import { assetTypeLabel } from '../../utils/labels'

interface AssetSpacesListProps {
  assetType: AssetType
  assetId: string
  /**
   * Whether the caller may manage the asset. Only then does the endpoint answer with the reader
   * circle of each space, and only then may an association be detached (#1939).
   */
  canManage?: boolean
}

/**
 * The "Zuordnungen" list: every space the asset is associated with, never filtered by the caller's
 * own space membership - a manager sees every association and may detach each one unilaterally
 * (docs/features/spaces-and-assets.md#assets-in-einen-space-assoziieren), a plain reader sees the
 * space names alone.
 */
export default function AssetSpacesList({
  assetType,
  assetId,
  canManage = true,
}: AssetSpacesListProps) {
  const [associations, setAssociations] = useState<AssetSpaceAssociationResponse[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const noun = assetTypeLabel(assetType)

  useEffect(() => {
    let cancelled = false
    getAssetSpaceAssociations(assetType, assetId)
      .then((data) => {
        if (!cancelled) setAssociations(data)
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
      setAssociations((prev) => prev?.filter((a) => a.spaceId !== spaceId) ?? null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Lösen fehlgeschlagen')
    }
  }

  return (
    <Box>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {associations === null ? (
        // Loading without a layout jump (guidelines 5.7) - one skeleton row where the first
        // association will land.
        <Skeleton variant="rounded" height={32} sx={{ maxWidth: 360 }} />
      ) : associations.length === 0 ? (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Diese {noun} ist derzeit keinem Space zugeordnet. Die Zuordnung erfolgt in den
          Einstellungen des jeweiligen Space.
        </Typography>
      ) : (
        <Stack spacing={1}>
          {associations.map((association) => (
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
        </Stack>
      )}
    </Box>
  )
}
