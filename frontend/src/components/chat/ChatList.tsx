import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import IconButton from '@mui/material/IconButton'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemSecondaryAction from '@mui/material/ListItemSecondaryAction'
import ListItemText from '@mui/material/ListItemText'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DeleteIcon from '@mui/icons-material/Delete'
import EditIcon from '@mui/icons-material/Edit'
import { useLocation, useNavigate } from 'react-router'
import type { ChatSummary } from '../../types/api'
import { useChatListStore } from '../../stores/chatListStore'

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
  const createChatInSpace = useChatListStore((s) => s.createChatInSpace)
  const renameChat = useChatListStore((s) => s.renameChat)
  const deleteChatFromList = useChatListStore((s) => s.deleteChatFromList)

  const [renamingChatId, setRenamingChatId] = useState<string | null>(null)
  const [renameValue, setRenameValue] = useState('')

  useEffect(() => {
    if (chats === undefined) {
      void loadChats(spaceId)
    }
  }, [spaceId, chats, loadChats])

  async function handleNewChat() {
    const chat = await createChatInSpace(spaceId)
    navigate(`/spaces/${spaceId}/chats/${chat.id}`)
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
      <Button
        variant="outlined"
        startIcon={<AddIcon />}
        onClick={handleNewChat}
        sx={{ mb: 1.5, borderRadius: 2, textTransform: 'none' }}
      >
        Neuer Chat
      </Button>

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
              <ListItemButton
                key={chat.id}
                selected={active}
                onClick={
                  isRenaming ? undefined : () => navigate(`/spaces/${spaceId}/chats/${chat.id}`)
                }
                sx={{ borderRadius: 2, mb: 0.5, pr: 9 }}
              >
                {isRenaming ? (
                  <TextField
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
                    slotProps={{ primary: { noWrap: true } }}
                  />
                )}
                {!isRenaming && (
                  <ListItemSecondaryAction>
                    <IconButton
                      size="small"
                      aria-label={`Chat „${chatTitle(chat)}“ umbenennen`}
                      onClick={(e) => {
                        e.stopPropagation()
                        startRename(chat)
                      }}
                    >
                      <EditIcon fontSize="small" />
                    </IconButton>
                    <IconButton
                      size="small"
                      aria-label={`Chat „${chatTitle(chat)}“ löschen`}
                      onClick={(e) => {
                        e.stopPropagation()
                        void handleDelete(chat)
                      }}
                    >
                      <DeleteIcon fontSize="small" />
                    </IconButton>
                  </ListItemSecondaryAction>
                )}
              </ListItemButton>
            )
          })}
        </List>
      )}
    </Box>
  )
}
