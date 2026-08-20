import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import IconButton from '@mui/material/IconButton'
import Divider from '@mui/material/Divider'
import ListItemIcon from '@mui/material/ListItemIcon'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemText from '@mui/material/ListItemText'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DeleteIcon from '@mui/icons-material/Delete'
import EditIcon from '@mui/icons-material/Edit'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import { ThemeProvider } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import { useLocation, useNavigate } from 'react-router'
import { blue } from '../../theme/tokens'
import type { ChatSummary } from '../../types/api'
import { useChatListStore } from '../../stores/chatListStore'
import { useSpaceStore } from '../../stores/spaceStore'

function chatTitle(chat: ChatSummary): string {
  return chat.title?.trim() || 'Unbenannter Chat'
}

interface ChatListProps {
  spaceId: string
  /** Rendered left of the "+ Neu" action, in the same row (mockup 1a's section head). */
  header?: ReactNode
  /**
   * Theme for the context menu. Mockup 1a shows light panels even over the navy sidebar, so
   * the Sidebar passes the app theme in here - the list itself stays on the sidebar theme.
   */
  menuTheme?: Theme
}

export default function ChatList({ spaceId, header, menuTheme }: ChatListProps) {
  const navigate = useNavigate()
  const location = useLocation()
  const chats = useChatListStore((s) => s.chatsBySpaceId[spaceId])
  const isLoading = useChatListStore((s) => s.isLoading)
  const error = useChatListStore((s) => s.error)
  const loadChats = useChatListStore((s) => s.loadChats)
  const renameChat = useChatListStore((s) => s.renameChat)
  const deleteChatFromList = useChatListStore((s) => s.deleteChatFromList)
  // #543/#613 review, nit c: an archived space accepts no new chats - the "Neuer Chat" button is
  // disabled rather than hidden, so it stays a stable click target and the reason is explained via
  // its tooltip instead of the button silently vanishing.
  const isArchived = useSpaceStore((s) => s.spaces.find((space) => space.id === spaceId)?.archived)

  const [renamingChatId, setRenamingChatId] = useState<string | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [menuAnchor, setMenuAnchor] = useState<{ chatId: string; el: HTMLElement } | null>(null)

  useEffect(() => {
    if (chats === undefined) {
      void loadChats(spaceId)
    }
  }, [spaceId, chats, loadChats])

  // Routes through the not-yet-persisted "new" chat state instead of eagerly creating a chat here
  // - the first sent message is the only place a chat gets created (chatStore#sendMessage), so a
  // chat that's never actually used never gets persisted either (#548 review, nit a).
  function handleNewChat() {
    navigate(`/spaces/${spaceId}/chats/new`)
  }

  function startRename(chat: ChatSummary) {
    setRenamingChatId(chat.id)
    setRenameValue(chat.title ?? '')
  }

  async function commitRename(chatId: string) {
    const title = renameValue.trim()
    setRenamingChatId(null)
    if (!title) return
    await renameChat(spaceId, chatId, title)
  }

  async function handleDelete(chat: ChatSummary) {
    const confirmed = window.confirm(
      `„${chatTitle(chat)}“ wirklich löschen? Diese Aktion kann nicht rückgängig gemacht werden.`,
    )
    if (!confirmed) return
    await deleteChatFromList(spaceId, chat.id)
    if (location.pathname === `/spaces/${spaceId}/chats/${chat.id}`) {
      navigate(`/spaces/${spaceId}/chats/new`, { replace: true })
    }
  }

  return (
    <Box>
      {/* Mockup 1a: section head and the "+ Neu" action share one baseline row (#658). */}
      <Box
        sx={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', pb: 0.5 }}
      >
        {header ?? <span />}
        <Tooltip
          title={
            isArchived ? 'Dieser Space ist archiviert und nimmt keine neuen Chats mehr an' : ''
          }
        >
          <span>
            <Button
              variant="text"
              size="small"
              aria-label="Neuer Chat"
              startIcon={<AddIcon sx={{ fontSize: 13 }} />}
              onClick={handleNewChat}
              disabled={Boolean(isArchived)}
              // Mockup 1a: a quiet small link in blue-300 on the navy block, not a boxed button.
              sx={{ minHeight: 0, px: 0.75, py: 0.25, fontSize: 11.5, color: blue[300] }}
            >
              Neu
            </Button>
          </span>
        </Tooltip>
      </Box>

      {error && (
        <Typography color="error.main" variant="body2" sx={{ mb: 1 }}>
          {error}
        </Typography>
      )}

      {chats === undefined && isLoading ? (
        <Box sx={{ py: 2, display: 'flex', justifyContent: 'center' }}>
          <CircularProgress size={20} />
        </Box>
      ) : !chats || chats.length === 0 ? (
        <Typography color="text.secondary" variant="body2">
          Noch keine Chats in diesem Space.
        </Typography>
      ) : (
        <List sx={{ px: 0 }}>
          {chats.map((chat) => {
            const active = location.pathname === `/spaces/${spaceId}/chats/${chat.id}`
            const isRenaming = renamingChatId === chat.id
            return (
              <ListItem
                key={chat.id}
                disablePadding
                // Mockup 1a keeps chat rows quiet - the actions only surface on hover or
                // keyboard focus (#658). They stay in the tab order either way.
                sx={{
                  '& .MuiListItemSecondaryAction-root': {
                    opacity: menuAnchor?.chatId === chat.id ? 1 : 0,
                    transition: 'opacity 120ms',
                  },
                  '&:hover .MuiListItemSecondaryAction-root, &:focus-within .MuiListItemSecondaryAction-root':
                    { opacity: 1 },
                }}
                secondaryAction={
                  !isRenaming && (
                    // Mockup 1a: one quiet three-dot trigger per row, the actions live in a
                    // light dropdown (#658 Nachbesserung).
                    <IconButton
                      size="small"
                      aria-label={`Aktionen für Chat „${chatTitle(chat)}“`}
                      aria-haspopup="menu"
                      aria-expanded={menuAnchor?.chatId === chat.id ? 'true' : undefined}
                      onClick={(event) =>
                        setMenuAnchor({ chatId: chat.id, el: event.currentTarget })
                      }
                      sx={{ p: 0.5, borderRadius: '4px' }}
                    >
                      <MoreVertIcon sx={{ fontSize: 16 }} />
                    </IconButton>
                  )
                }
              >
                <ListItemButton
                  selected={active}
                  onClick={
                    isRenaming ? undefined : () => navigate(`/spaces/${spaceId}/chats/${chat.id}`)
                  }
                  sx={{ borderRadius: '6px', mb: 0.25, pr: 5.5, py: 0.5 }}
                >
                  {isRenaming ? (
                    <TextField
                      // The field appears only after the user chose "Umbenennen"; moving focus into it is
                      // the expected inline-edit behaviour. Re-verified in the closing audit, see #598.
                      // eslint-disable-next-line jsx-a11y-x/no-autofocus
                      autoFocus
                      size="small"
                      fullWidth
                      value={renameValue}
                      slotProps={{ htmlInput: { 'aria-label': 'Chat-Titel' } }}
                      onClick={(e) => e.stopPropagation()}
                      onChange={(e) => setRenameValue(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') {
                          e.preventDefault()
                          void commitRename(chat.id)
                        } else if (e.key === 'Escape') {
                          e.preventDefault()
                          setRenamingChatId(null)
                        }
                      }}
                      onBlur={() => void commitRename(chat.id)}
                    />
                  ) : (
                    <ListItemText
                      primary={chatTitle(chat)}
                      slotProps={{ primary: { noWrap: true, variant: 'body2' } }}
                    />
                  )}
                </ListItemButton>
              </ListItem>
            )
          })}
        </List>
      )}

      {(() => {
        const menuChat = chats?.find((chat) => chat.id === menuAnchor?.chatId)
        const menu = (
          <Menu
            anchorEl={menuAnchor?.el ?? null}
            open={Boolean(menuAnchor && menuChat)}
            onClose={() => setMenuAnchor(null)}
            slotProps={{ paper: { sx: { width: 190 } }, list: { 'aria-label': 'Chat-Aktionen' } }}
          >
            <MenuItem
              aria-label={menuChat ? `Chat „${chatTitle(menuChat)}“ umbenennen` : undefined}
              onClick={() => {
                setMenuAnchor(null)
                if (menuChat) startRename(menuChat)
              }}
            >
              <ListItemIcon>
                <EditIcon sx={{ fontSize: 15 }} />
              </ListItemIcon>
              Umbenennen
            </MenuItem>
            <Divider sx={{ my: 0.5 }} />
            <MenuItem
              aria-label={menuChat ? `Chat „${chatTitle(menuChat)}“ löschen` : undefined}
              onClick={() => {
                setMenuAnchor(null)
                if (menuChat) void handleDelete(menuChat)
              }}
              sx={{ color: 'error.main' }}
            >
              <ListItemIcon>
                <DeleteIcon sx={{ fontSize: 15, color: 'error.main' }} />
              </ListItemIcon>
              Löschen
            </MenuItem>
          </Menu>
        )
        return menuTheme ? <ThemeProvider theme={menuTheme}>{menu}</ThemeProvider> : menu
      })()}
    </Box>
  )
}
