import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { AssetType } from '../../types/api'
import { useSpaceStore } from '../../stores/spaceStore'
import AssetTilePicker, { assetPickKey, type AssetPick } from '../assets/AssetTilePicker'
import { successionAwareMessage } from '../succession/successionConflict'
import SectionHead from '../SectionHead'

/** The one disclosure about unreadable associations: no number, no name (ADR-0039). */
export const NOT_ALL_READABLE = 'Nicht alle zugeordneten Inhalte sind für Sie lesbar.'

export interface SpaceAssetAssociationSectionProps {
  spaceId: string
  /** A curator may associate and detach, a member only looks on. */
  canManage: boolean
  assetType: AssetType
  /** The asset types' own wording - heading, intro, empty state, picker. */
  texts: {
    heading: string
    intro: string
    loading: string
    empty: string
    pickerHeading: string
    associated: string
  }
}

/**
 * One tab of the space settings for one asset type: the associated assets of that type the caller
 * may read, and for curators the tile choice of further readable ones. The association grants
 * nobody access (docs/features/spaces-and-assets.md#assets-in-einen-space-assoziieren); the store
 * holds every type's associations, this section shows its own type's only.
 */
export default function SpaceAssetAssociationSection({
  spaceId,
  canManage,
  assetType,
  texts,
}: SpaceAssetAssociationSectionProps) {
  const storeError = useSpaceStore((s) => s.error)
  const allAssociations = useSpaceStore((s) => s.assetAssociations)
  const hasUnreadable = useSpaceStore((s) => s.hasUnreadableAssociations)
  const isLoading = useSpaceStore((s) => s.isLoadingAssetAssociations)
  const loadAssetAssociations = useSpaceStore((s) => s.loadAssetAssociations)
  const associateAsset = useSpaceStore((s) => s.associateAsset)
  const detachAsset = useSpaceStore((s) => s.detachAsset)
  const [localError, setLocalError] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)
  const [picked, setPicked] = useState<AssetPick[]>([])
  const [isAssociating, setIsAssociating] = useState(false)

  useEffect(() => {
    void loadAssetAssociations(spaceId)
  }, [loadAssetAssociations, spaceId])

  const associations = useMemo(
    () => allAssociations.filter((association) => association.assetType === assetType),
    [allAssociations, assetType],
  )
  const associatedKeys = useMemo(
    () => new Set(allAssociations.map((association) => assetPickKey(association))),
    [allAssociations],
  )

  async function associatePicked() {
    setLocalError(null)
    setSuccessMessage(null)
    setIsAssociating(true)
    const remaining: AssetPick[] = []
    let failure: string | null = null
    for (const pick of picked) {
      try {
        await associateAsset(spaceId, pick.assetType, pick.assetId)
      } catch (err) {
        remaining.push(pick)
        failure ??= successionAwareMessage(err, 'Zuordnung fehlgeschlagen')
      }
    }
    setPicked(remaining)
    setIsAssociating(false)
    if (failure) {
      setLocalError(failure)
    } else {
      setSuccessMessage(texts.associated)
    }
  }

  return (
    <Stack spacing={2}>
      {/* Die h2 dieses Panels unter der h1 der Seite. */}
      <SectionHead>{texts.heading}</SectionHead>
      {(localError || storeError) && <Alert severity="error">{localError ?? storeError}</Alert>}
      {successMessage && <Alert severity="success">{successMessage}</Alert>}
      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
        {texts.intro}
      </Typography>
      {hasUnreadable && !isLoading && <Alert severity="info">{NOT_ALL_READABLE}</Alert>}
      {isLoading ? (
        <Typography sx={{ color: 'text.secondary' }}>{texts.loading}</Typography>
      ) : associations.length === 0 ? (
        <Typography sx={{ color: 'text.secondary' }}>{texts.empty}</Typography>
      ) : (
        <Stack spacing={0}>
          {associations.map((association) => (
            <Box
              key={association.assetId}
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                py: 1.25,
                '& + &': { borderTop: 1, borderColor: 'divider' },
              }}
            >
              <Typography>{association.name}</Typography>
              {canManage && (
                <Button
                  color="error"
                  size="small"
                  aria-label={`${association.name} lösen`}
                  onClick={async () => {
                    setLocalError(null)
                    try {
                      await detachAsset(spaceId, association.assetId)
                    } catch (err) {
                      setLocalError(err instanceof Error ? err.message : 'Lösen fehlgeschlagen')
                    }
                  }}
                >
                  Lösen
                </Button>
              )}
            </Box>
          ))}
        </Stack>
      )}
      {canManage && (
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          <Typography component="h3" sx={{ fontSize: 15, fontWeight: 600 }}>
            {texts.pickerHeading}
          </Typography>
          <AssetTilePicker
            types={[assetType]}
            value={picked}
            onChange={setPicked}
            excludedKeys={associatedKeys}
            aria-label={texts.pickerHeading}
          />
          <Box>
            <Button
              variant="contained"
              disabled={picked.length === 0 || isAssociating}
              onClick={() => void associatePicked()}
            >
              Zuordnen
            </Button>
          </Box>
        </Stack>
      )}
    </Stack>
  )
}
