import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { LibraryListResponse } from '../../types/api'
import { getLibraries } from '../../services/api'
import { useSpaceStore } from '../../stores/spaceStore'
import { assetTypeLabel } from '../../utils/labels'
import { successionAwareMessage } from '../succession/successionConflict'
import SectionHead from '../SectionHead'

interface SpaceKnowledgeSectionProps {
  spaceId: string
  /** #203: ein Kurator darf Bibliotheken zuordnen und lösen, ein Mitglied nur zusehen. */
  canManage: boolean
}

/**
 * Der Reiter „Wissen" der Space-Einstellungen (#1917): die dem Space zugeordneten
 * Wissensbibliotheken, und für Kuratoren die Zuordnung weiterer.
 */
export default function SpaceKnowledgeSection({ spaceId, canManage }: SpaceKnowledgeSectionProps) {
  const storeError = useSpaceStore((s) => s.error)
  const assetAssociations = useSpaceStore((s) => s.assetAssociations)
  const isLoadingAssetAssociations = useSpaceStore((s) => s.isLoadingAssetAssociations)
  const loadAssetAssociations = useSpaceStore((s) => s.loadAssetAssociations)
  const associateAsset = useSpaceStore((s) => s.associateAsset)
  const detachAsset = useSpaceStore((s) => s.detachAsset)
  const [localError, setLocalError] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)
  const [readableLibraries, setReadableLibraries] = useState<LibraryListResponse[]>([])
  const [selectedLibrary, setSelectedLibrary] = useState<LibraryListResponse | null>(null)

  useEffect(() => {
    void loadAssetAssociations(spaceId)
  }, [loadAssetAssociations, spaceId])

  useEffect(() => {
    // #203: a CURATOR may only associate a library they themselves can read - GET /v1/libraries
    // already returns exactly that set, and the backend re-checks the same rule.
    void getLibraries()
      .then(setReadableLibraries)
      .catch(() => setReadableLibraries([]))
  }, [])

  const associableLibraries = useMemo(() => {
    const associatedIds = new Set(assetAssociations.map((a) => a.assetId))
    return readableLibraries.filter((l) => !associatedIds.has(l.id))
  }, [readableLibraries, assetAssociations])

  return (
    <Stack spacing={2}>
      {/* Die h2 dieses Panels unter der h1 der Seite. */}
      <SectionHead>Zugeordnete Bibliotheken</SectionHead>
      {(localError || storeError) && <Alert severity="error">{localError ?? storeError}</Alert>}
      {successMessage && <Alert severity="success">{successMessage}</Alert>}
      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
        Eine Zuordnung stellt eine Bibliothek in diesem Space bereit, gewährt aber niemandem
        zusätzlichen Zugriff — nur Mitglieder mit eigenem Leserecht auf die Bibliothek sehen ihre
        Treffer.
      </Typography>
      {isLoadingAssetAssociations ? (
        <Typography sx={{ color: 'text.secondary' }}>Bibliotheken werden geladen …</Typography>
      ) : assetAssociations.length === 0 ? (
        <Typography sx={{ color: 'text.secondary' }}>
          Diesem Space sind keine Bibliotheken zugeordnet.
        </Typography>
      ) : (
        <Stack spacing={0}>
          {assetAssociations.map((association) => (
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
            options={associableLibraries}
            getOptionLabel={(option) => option.name}
            noOptionsText="Keine Treffer"
            value={selectedLibrary}
            onChange={(_event, value) => setSelectedLibrary(value)}
            renderInput={(params) => (
              <TextField {...params} label="Bibliothek" placeholder="Bibliothek suchen …" />
            )}
            isOptionEqualToValue={(option, value) => option.id === value.id}
            sx={{ minWidth: 280 }}
          />
          <Button
            variant="contained"
            disabled={!selectedLibrary}
            onClick={async () => {
              if (!selectedLibrary) return
              setLocalError(null)
              try {
                await associateAsset(spaceId, 'KNOWLEDGE_LIBRARY', selectedLibrary.id)
                setSelectedLibrary(null)
                setSuccessMessage('Bibliothek zugeordnet')
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
