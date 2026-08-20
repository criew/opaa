import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import IconButton from '@mui/material/IconButton'
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
import { useLocation, useNavigate } from 'react-router'
import type { ChatSummary } from '../../types/api'
import { useChatListStore } from '../../stores/chatListStore'
import { useSpaceStore } from '../../stores/spaceStore'

function chatTitle(chat: ChatSummary): string {
  return chat.title?.trim() || 'Unbenannter Chat'
}

function formatUpdatedAt(updatedAt: string): string {
  return new Date(updatedAt).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
}

interface ChatListProps {
  spaceId: string
}

export default function ChatList({ spaceId }: ChatListProps) {
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
      <Tooltip
        title={isArchived ? 'Dieser Space ist archiviert und nimmt keine neuen Chats mehr an' : ''}
      >
        <span>
          <Button
            variant="text"
            size="small"
            startIcon={<AddIcon sx={{ fontSize: 14 }} />}
            onClick={handleNewChat}
            disabled={Boolean(isArchived)}
            // Mockup 1a renders "+ Neu" as a quiet small link, not a boxed button (#658).
            sx={{ mb: 0.5, minHeight: 0, px: 1, py: 0.25, fontSize: 12.5 }}
          >
            Neuer Chat
          </Button>
        </span>
      </Tooltip>

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
                    opacity: 0,
                    transition: 'opacity 120ms',
                  },
                  '&:hover .MuiListItemSecondaryAction-root, &:focus-within .MuiListItemSecondaryAction-root':
                    { opacity: 1 },
                }}
                secondaryAction={
                  !isRenaming && (
                    <>
                      <IconButton
                        size="small"
                        aria-label={`Chat „${chatTitle(chat)}“ umbenennen`}
                        onClick={() => startRename(chat)}
                        sx={{ p: 0.5 }}
                      >
                        <EditIcon sx={{ fontSize: 15 }} />
                      </IconButton>
                      <IconButton
                        size="small"
                        aria-label={`Chat „${chatTitle(chat)}“ löschen`}
                        onClick={() => void handleDelete(chat)}
                        sx={{ p: 0.5 }}
                      >
                        <DeleteIcon sx={{ fontSize: 15 }} />
                      </IconButton>
                    </>
                  )
                }
              >
                <ListItemButton
                  selected={active}
                  onClick={
                    isRenaming ? undefined : () => navigate(`/spaces/${spaceId}/chats/${chat.id}`)
                  }
                  sx={{ borderRadius: '6px', mb: 0.25, pr: 8, py: 0.5 }}
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
                      secondary={formatUpdatedAt(chat.updatedAt)}
                      slotProps={{
                        primary: { noWrap: true, variant: 'body2' },
                        secondary: { variant: 'caption' },
                      }}
                    />
                  )}
                </ListItemButton>
              </ListItem>
            )
          })}
        </List>
      )}
    </Box>
  )
}
