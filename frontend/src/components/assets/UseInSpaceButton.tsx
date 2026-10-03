import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
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
import type { SpaceCreateLocationState } from './assetPick'

interface UseInSpaceTarget {
  assetType: AssetType
  assetId: string
  name: string
  /** Called after an association was created, e.g. to reload counts and lists. */
  onAssociated?: () => void
}

interface UseInSpaceButtonProps extends UseInSpaceTarget {
  size?: 'small' | 'medium'
}

interface UseInSpaceDialogProps extends UseInSpaceTarget {
  open: boolean
  onClose: () => void
}

/** The spaces in which the person may associate: CURATOR or ADMIN, and not archived. */
function curatedSpaces(spaces: SpaceListResponse[]): SpaceListResponse[] {
  return spaces.filter(
    (space) => !space.archived && (space.userRole === 'CURATOR' || space.userRole === 'ADMIN'),
  )
}

/**
 * The choice behind "In Space verwenden" (ADR-0039, Entscheidung 4): the spaces the person curates,
 * one click associates - or starts a new space with the asset already chosen.
 */
export function UseInSpaceDialog({
  assetType,
  assetId,
  name,
  open,
  onClose,
  onAssociated,
}: UseInSpaceDialogProps) {
  const navigate = useNavigate()
  const [spaces, setSpaces] = useState<SpaceListResponse[] | null>(null)
  const [associated, setAssociated] = useState<Set<string>>(new Set())
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!open) return
    let cancelled = false
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

  /** Closes and forgets the loaded choice, so the next opening starts from "wird geladen". */
  function close() {
    setSpaces(null)
    setError(null)
    onClose()
  }

  async function associate(space: SpaceListResponse) {
    setBusy(true)
    setError(null)
    try {
      await associateSpaceAsset(space.id, assetType, assetId)
      notify(`„${name}“ ist jetzt im Space „${space.name}“ zugeordnet.`, 'success')
      onAssociated?.()
      close()
    } catch (err) {
      setError(successionAwareMessage(err, 'Zuordnung fehlgeschlagen'))
    } finally {
      setBusy(false)
    }
  }

  function startNewSpace() {
    const state: SpaceCreateLocationState = { preselect: { assetType, assetId, name } }
    close()
    navigate('/spaces/new', { state })
  }

  const titleId = `use-in-space-${assetId}`
  return (
    <Dialog open={open} onClose={close} aria-labelledby={titleId} fullWidth>
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
                  <ListItem key={space.id} disablePadding>
                    <ListItemButton
                      disabled={busy || already}
                      onClick={() => void associate(space)}
                    >
                      <ListItemText
                        primary={space.name}
                        secondary={already ? 'Bereits zugeordnet' : undefined}
                      />
                    </ListItemButton>
                  </ListItem>
                )
              })}
              <ListItem disablePadding>
                <ListItemButton disabled={busy} onClick={startNewSpace}>
                  <AddIcon aria-hidden sx={{ mr: 1.5, color: 'text.secondary' }} />
                  <ListItemText primary="Neuen Space damit anlegen" />
                </ListItemButton>
              </ListItem>
            </List>
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={close}>Abbrechen</Button>
      </DialogActions>
    </Dialog>
  )
}

/** "In Space verwenden" as a button of its own, opening {@link UseInSpaceDialog}. */
export default function UseInSpaceButton({
  assetType,
  assetId,
  name,
  size = 'medium',
  onAssociated,
}: UseInSpaceButtonProps) {
  const [open, setOpen] = useState(false)
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
      <UseInSpaceDialog
        assetType={assetType}
        assetId={assetId}
        name={name}
        open={open}
        onClose={() => setOpen(false)}
        onAssociated={onAssociated}
      />
    </>
  )
}
