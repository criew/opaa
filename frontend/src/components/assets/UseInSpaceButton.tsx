import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemText from '@mui/material/ListItemText'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import WorkspacesOutlinedIcon from '@mui/icons-material/WorkspacesOutlined'
import type { AssetType, SpaceListResponse } from '../../types/api'
import { associateSpaceAsset, getAssetSpaceAssociations } from '../../services/assetApi'
import { getSpaces } from '../../services/spaceApi'
import { notify } from '../../stores/notificationStore'
import { successionAwareMessage } from '../succession/successionConflict'
import type { SpaceCreateLocationState } from './AssetTilePicker'

interface UseInSpaceButtonProps {
  assetType: AssetType
  assetId: string
  name: string
  size?: 'small' | 'medium'
}

/** The spaces in which the person may associate: CURATOR or ADMIN, and not archived. */
function curatedSpaces(spaces: SpaceListResponse[]): SpaceListResponse[] {
  return spaces.filter(
    (space) => !space.archived && (space.userRole === 'CURATOR' || space.userRole === 'ADMIN'),
  )
}

/**
 * "In Space verwenden" (ADR-0039, Entscheidung 4): one click opens the choice of the spaces the
 * person curates, the second associates - or starts a new space with the asset already chosen.
 */
export default function UseInSpaceButton({
  assetType,
  assetId,
  name,
  size = 'medium',
}: UseInSpaceButtonProps) {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [spaces, setSpaces] = useState<SpaceListResponse[] | null>(null)
  const [associated, setAssociated] = useState<Set<string>>(new Set())
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!open) return
    let cancelled = false
    setError(null)
    setSpaces(null)
    void Promise.all([
      getSpaces(),
      getAssetSpaceAssociations(assetType, assetId).catch(() => ({ items: [] })),
    ])
      .then(([list, links]) => {
        if (cancelled) return
        setSpaces(curatedSpaces(list))
        setAssociated(new Set(links.items.map((item) => item.spaceId)))
      })
      .catch((err) => {
        if (cancelled) return
        setSpaces([])
        setError(err instanceof Error ? err.message : 'Spaces konnten nicht geladen werden.')
      })
    return () => {
      cancelled = true
    }
  }, [open, assetType, assetId])

  async function associate(space: SpaceListResponse) {
    setBusy(true)
    setError(null)
    try {
      await associateSpaceAsset(space.id, assetType, assetId)
      notify(`„${name}“ ist jetzt im Space „${space.name}“ zugeordnet.`, 'success')
      setOpen(false)
    } catch (err) {
      setError(successionAwareMessage(err, 'Zuordnung fehlgeschlagen'))
    } finally {
      setBusy(false)
    }
  }

  function startNewSpace() {
    const state: SpaceCreateLocationState = { preselect: { assetType, assetId, name } }
    setOpen(false)
    navigate('/spaces/new', { state })
  }

  const titleId = `use-in-space-${assetId}`
  return (
    <>
      <Button
        variant="outlined"
        size={size}
        startIcon={<WorkspacesOutlinedIcon />}
        onClick={() => setOpen(true)}
        aria-label={`„${name}“ in Space verwenden`}
      >
        In Space verwenden
      </Button>
      <Dialog open={open} onClose={() => setOpen(false)} aria-labelledby={titleId} fullWidth>
        <DialogTitle id={titleId}>„{name}“ in Space verwenden</DialogTitle>
        <DialogContent>
          {error && (
            <Alert severity="error" sx={{ mb: 1.5 }}>
              {error}
            </Alert>
          )}
          {spaces === null ? (
            <Typography sx={{ color: 'text.secondary' }}>Spaces werden geladen …</Typography>
          ) : (
            <>
              {spaces.length === 0 && (
                <Typography sx={{ color: 'text.secondary', mb: 1 }}>
                  Sie kuratieren noch keinen Space. Legen Sie einen neuen an.
                </Typography>
              )}
              <List aria-label="Spaces, die Sie kuratieren" sx={{ py: 0 }}>
                {spaces.map((space) => {
                  const already = associated.has(space.id)
                  return (
                    <ListItemButton
                      key={space.id}
                      disabled={busy || already}
                      onClick={() => void associate(space)}
                    >
                      <ListItemText
                        primary={space.name}
                        secondary={already ? 'Bereits zugeordnet' : undefined}
                      />
                    </ListItemButton>
                  )
                })}
                <ListItemButton disabled={busy} onClick={startNewSpace}>
                  <AddIcon aria-hidden sx={{ mr: 1.5, color: 'text.secondary' }} />
                  <ListItemText primary="Neuen Space damit anlegen" />
                </ListItemButton>
              </List>
            </>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Abbrechen</Button>
        </DialogActions>
      </Dialog>
    </>
  )
}
