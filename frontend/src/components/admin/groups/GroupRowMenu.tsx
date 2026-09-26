import { useState } from 'react'
import Box from '@mui/material/Box'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import PeopleOutlineIcon from '@mui/icons-material/PeopleOutlined'
import SwapHorizOutlinedIcon from '@mui/icons-material/SwapHorizOutlined'
import type { GroupListResponse } from '../../../types/api'
import { confirmAction } from '../../../stores/confirmStore'
import { useGroupStore } from '../../../stores/groupStore'
import { notify } from '../../../stores/notificationStore'
import { GROUP_DELETE_CONSEQUENCE, PROVIDER_GROUP_DELETE_REASON } from './groupListLabels'

interface GroupRowMenuProps {
  group: GroupListResponse
  onEdit: (group: GroupListResponse) => void
  onMembers: (group: GroupListResponse) => void
  onTransfer: (group: GroupListResponse) => void
  /** After a deletion: the page reloads, because a row left it. */
  onDeleted: () => void
}

/**
 * Das Zeilenmenü einer Gruppe (#1978), gebaut wie das eines Kontos: Bearbeiten, Mitglieder und
 * „Wirkungen übertragen" öffnen ihren Dialog; „Löschen" steht nachrangig unter einer Trennlinie
 * und legt seine Konsequenz vorher im Bestätigungs-Overlay vor. Ein Eintrag, der für diese
 * Gruppe nicht gilt, ist abgeblendet und nennt den Grund.
 */
export default function GroupRowMenu({
  group,
  onEdit,
  onMembers,
  onTransfer,
  onDeleted,
}: GroupRowMenuProps) {
  const deleteExistingGroup = useGroupStore((s) => s.deleteExistingGroup)
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const [busy, setBusy] = useState(false)
  const close = () => setAnchor(null)
  const deleteDisabled = group.kind !== 'AD_HOC'
  const reasonId = `group-${group.id}-menu-reason`

  function open(action: (group: GroupListResponse) => void) {
    close()
    action(group)
  }

  async function remove() {
    close()
    if (
      !(await confirmAction({
        question: `„${group.name}“ löschen?`,
        consequence: GROUP_DELETE_CONSEQUENCE,
        confirmLabel: 'Löschen',
        tone: 'danger',
      }))
    ) {
      return
    }
    setBusy(true)
    try {
      await deleteExistingGroup(group.id)
      notify(`„${group.name}“ wurde gelöscht.`, 'success')
      onDeleted()
    } catch (err) {
      notify(
        err instanceof Error ? err.message : 'Die Gruppe konnte nicht gelöscht werden.',
        'error',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <Tooltip title="Weitere Aktionen">
        <span>
          <IconButton
            size="small"
            aria-label={`Aktionen für „${group.name}“`}
            aria-haspopup="true"
            disabled={busy}
            onClick={(e) => setAnchor(e.currentTarget)}
          >
            <MoreVertIcon fontSize="small" />
          </IconButton>
        </span>
      </Tooltip>
      <Menu
        anchorEl={anchor}
        open={anchor !== null}
        onClose={close}
        slotProps={{ list: { 'aria-label': `Aktionen für „${group.name}“` } }}
      >
        <MenuItem onClick={() => open(onEdit)}>
          <ListItemIcon>
            <EditOutlinedIcon fontSize="small" />
          </ListItemIcon>
          Bearbeiten
        </MenuItem>
        <MenuItem onClick={() => open(onMembers)}>
          <ListItemIcon>
            <PeopleOutlineIcon fontSize="small" />
          </ListItemIcon>
          Mitglieder
        </MenuItem>
        <MenuItem onClick={() => open(onTransfer)}>
          <ListItemIcon>
            <SwapHorizOutlinedIcon fontSize="small" />
          </ListItemIcon>
          Wirkungen übertragen
        </MenuItem>
        <Divider />
        <MenuItem
          onClick={() => void remove()}
          disabled={deleteDisabled}
          title={deleteDisabled ? PROVIDER_GROUP_DELETE_REASON : undefined}
          aria-describedby={deleteDisabled ? reasonId : undefined}
        >
          <ListItemIcon>
            <DeleteOutlineIcon fontSize="small" color={deleteDisabled ? undefined : 'error'} />
          </ListItemIcon>
          <Typography sx={{ fontSize: 14, color: deleteDisabled ? undefined : 'error.main' }}>
            Löschen
          </Typography>
        </MenuItem>
        {deleteDisabled && (
          // Ein `<li role="presentation">` statt eines Absatzes: Ein `<p>` unter `role="menu"`
          // verletzt `aria-required-children`. Der abgeblendete Eintrag verweist hierher.
          <Box
            component="li"
            role="presentation"
            id={reasonId}
            sx={{ px: 2, py: 1, maxWidth: 320 }}
          >
            <Typography component="span" sx={{ fontSize: 11.5, color: 'text.secondary' }}>
              {PROVIDER_GROUP_DELETE_REASON}
            </Typography>
          </Box>
        )}
      </Menu>
    </>
  )
}
