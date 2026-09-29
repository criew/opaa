import { useState } from 'react'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import ArchiveOutlinedIcon from '@mui/icons-material/ArchiveOutlined'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import PushPinOutlinedIcon from '@mui/icons-material/PushPinOutlined'
import UnarchiveOutlinedIcon from '@mui/icons-material/UnarchiveOutlined'
import type { ChatBulkAction, ChatSummary } from '../../types/api'
import { chatTitle } from './chatListSections'

export interface ChatRowActions {
  /** Archives, brings back or deletes the given chats - the same path as the bulk actions. */
  onAction: (action: ChatBulkAction, chats: ChatSummary[]) => void
  onPin: (chat: ChatSummary, pinned: boolean) => void
}

interface ChatRowMenuProps extends ChatRowActions {
  chat: ChatSummary
  archived: boolean
  disabled?: boolean
}

/**
 * The row menu of one chat on the chats page, built like the row menus of the administration
 * lists: the everyday actions first, "Löschen" last under a divider. An archived chat is brought
 * back rather than pinned or archived.
 */
export default function ChatRowMenu({
  chat,
  archived,
  disabled,
  onAction,
  onPin,
}: ChatRowMenuProps) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const title = chatTitle(chat)
  const close = () => setAnchor(null)
  const run = (action: () => void) => {
    close()
    action()
  }

  return (
    <>
      <Tooltip title="Weitere Aktionen">
        <span>
          <IconButton
            size="small"
            aria-label={`Aktionen für „${title}“`}
            aria-haspopup="true"
            disabled={disabled}
            onClick={(event) => setAnchor(event.currentTarget)}
          >
            <MoreVertIcon fontSize="small" />
          </IconButton>
        </span>
      </Tooltip>
      <Menu
        anchorEl={anchor}
        open={anchor !== null}
        onClose={close}
        slotProps={{ list: { 'aria-label': `Aktionen für „${title}“` } }}
      >
        {archived ? (
          <MenuItem onClick={() => run(() => onAction('UNARCHIVE', [chat]))}>
            <ListItemIcon>
              <UnarchiveOutlinedIcon fontSize="small" />
            </ListItemIcon>
            Zurückholen
          </MenuItem>
        ) : (
          [
            <MenuItem key="pin" onClick={() => run(() => onPin(chat, !chat.pinnedAt))}>
              <ListItemIcon>
                <PushPinOutlinedIcon fontSize="small" />
              </ListItemIcon>
              {chat.pinnedAt ? 'Lösen' : 'Anheften'}
            </MenuItem>,
            <MenuItem key="archive" onClick={() => run(() => onAction('ARCHIVE', [chat]))}>
              <ListItemIcon>
                <ArchiveOutlinedIcon fontSize="small" />
              </ListItemIcon>
              Archivieren
            </MenuItem>,
          ]
        )}
        <Divider />
        <MenuItem onClick={() => run(() => onAction('DELETE', [chat]))}>
          <ListItemIcon>
            <DeleteOutlineIcon fontSize="small" color="error" />
          </ListItemIcon>
          <Typography sx={{ fontSize: 14, color: 'error.main' }}>Löschen</Typography>
        </MenuItem>
      </Menu>
    </>
  )
}
