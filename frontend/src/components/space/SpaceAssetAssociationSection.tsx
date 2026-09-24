import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { AssetType } from '../../types/api'
import { useSpaceStore } from '../../stores/spaceStore'
import { assetTypeLabel } from '../../utils/labels'
import { successionAwareMessage } from '../succession/successionConflict'
import SectionHead from '../SectionHead'

/** An asset the caller may read and therefore associate - id and display name suffice. */
export interface AssociableAsset {
  id: string
  name: string
}

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
    pickerLabel: string
    pickerPlaceholder: string
    associated: string
  }
  /** The assets of this type the caller may read - the backend re-checks the same rule. */
  loadReadable: () => Promise<AssociableAsset[]>
}

/**
 * One tab of the space settings for one asset type: the associated assets of that type, and
 * for curators the association of further readable ones. The association grants nobody access
 * (docs/features/spaces-and-assets.md#assets-in-einen-space-assoziieren); the store holds every
 * type's associations, this section shows its own type's only.
 */
export default function SpaceAssetAssociationSection({
  spaceId,
  canManage,
  assetType,
  texts,
  loadReadable,
}: SpaceAssetAssociationSectionProps) {
  const storeError = useSpaceStore((s) => s.error)
  const allAssociations = useSpaceStore((s) => s.assetAssociations)
  const isLoading = useSpaceStore((s) => s.isLoadingAssetAssociations)
  const loadAssetAssociations = useSpaceStore((s) => s.loadAssetAssociations)
  const associateAsset = useSpaceStore((s) => s.associateAsset)
  const detachAsset = useSpaceStore((s) => s.detachAsset)
  const [localError, setLocalError] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)
  const [readable, setReadable] = useState<AssociableAsset[]>([])
  const [selected, setSelected] = useState<AssociableAsset | null>(null)

  useEffect(() => {
    void loadAssetAssociations(spaceId)
  }, [loadAssetAssociations, spaceId])

  useEffect(() => {
    void loadReadable()
      .then(setReadable)
      .catch(() => setReadable([]))
  }, [loadReadable])

  const associations = useMemo(
    () => allAssociations.filter((association) => association.assetType === assetType),
    [allAssociations, assetType],
  )

  const associable = useMemo(() => {
    const associatedIds = new Set(associations.map((a) => a.assetId))
    return readable.filter((asset) => !associatedIds.has(asset.id))
  }, [readable, associations])

  return (
    <Stack spacing={2}>
      {/* Die h2 dieses Panels unter der h1 der Seite. */}
      <SectionHead>{texts.heading}</SectionHead>
      {(localError || storeError) && <Alert severity="error">{localError ?? storeError}</Alert>}
      {successMessage && <Alert severity="success">{successMessage}</Alert>}
      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
        {texts.intro}
      </Typography>
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
              <Typography
                sx={
                  association.readableByCaller
                    ? undefined
                    : { color: 'text.secondary', fontStyle: 'italic' }
                }
              >
                {association.readableByCaller
                  ? association.name
                  : `${assetTypeLabel(association.assetType)} ohne eigenen Zugriff`}
              </Typography>
              {canManage && (
                <Button
                  color="error"
                  size="small"
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
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} sx={{ pt: 1 }}>
          <Autocomplete
            options={associable}
            getOptionLabel={(option) => option.name}
            noOptionsText="Keine Treffer"
            value={selected}
            onChange={(_event, value) => setSelected(value)}
            renderInput={(params) => (
              <TextField
                {...params}
                label={texts.pickerLabel}
                placeholder={texts.pickerPlaceholder}
              />
            )}
            isOptionEqualToValue={(option, value) => option.id === value.id}
            sx={{ minWidth: 280 }}
          />
          <Button
            variant="contained"
            disabled={!selected}
            onClick={async () => {
              if (!selected) return
              setLocalError(null)
              try {
                await associateAsset(spaceId, assetType, selected.id)
                setSelected(null)
                setSuccessMessage(texts.associated)
              } catch (err) {
                setLocalError(successionAwareMessage(err, 'Zuordnung fehlgeschlagen'))
              }
            }}
          >
            Zuordnen
          </Button>
        </Stack>
      )}
    </Stack>
  )
}
