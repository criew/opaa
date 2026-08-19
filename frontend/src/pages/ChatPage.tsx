import { useEffect } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import { useNavigate, useParams } from 'react-router'
import MessageList from '../components/chat/MessageList'
import ChatInput from '../components/chat/ChatInput'
import { useChatStore } from '../stores/chatStore'

export default function ChatPage() {
  const { spaceId, chatId: routeChatId } = useParams<{ spaceId: string; chatId: string }>()
  const navigate = useNavigate()

  const messages = useChatStore((s) => s.messages)
  const isLoading = useChatStore((s) => s.isLoading)
  const isLoadingChat = useChatStore((s) => s.isLoadingChat)
  const error = useChatStore((s) => s.error)
  const sendMessage = useChatStore((s) => s.sendMessage)
  const loadChat = useChatStore((s) => s.loadChat)
  const startNewChat = useChatStore((s) => s.startNewChat)
  const storeSpaceId = useChatStore((s) => s.spaceId)
  const storeChatId = useChatStore((s) => s.chatId)

  const isNewChat = !routeChatId || routeChatId === 'new'

  // Loads the requested chat's history, or resets to a blank not-yet-persisted chat for the
  // current space - whichever the route asks for. Only re-runs when the route itself changes, not
  // on every store update.
  useEffect(() => {
    if (!spaceId) return
    if (!isNewChat && routeChatId) {
      void loadChat(routeChatId)
    } else {
      startNewChat(spaceId)
    }
  }, [spaceId, routeChatId, isNewChat, loadChat, startNewChat])

  // The first message on a not-yet-persisted chat creates it implicitly (chatStore#sendMessage) -
  // once that happened, the URL is replaced to point at the real chat id so a reload restores it.
  useEffect(() => {
    if (isNewChat && storeChatId && storeSpaceId) {
      navigate(`/spaces/${storeSpaceId}/chats/${storeChatId}`, { replace: true })
    }
  }, [isNewChat, storeChatId, storeSpaceId, navigate])

  if (isLoadingChat) {
    return (
      <Box sx={{ flexGrow: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <CircularProgress />
      </Box>
    )
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', flexGrow: 1, minHeight: 0 }}>
      {error && (
        <Alert severity="error" sx={{ m: 2 }}>
          {error}
        </Alert>
      )}
      <MessageList messages={messages} isLoading={isLoading} />
      <ChatInput onSend={(message) => sendMessage(message)} disabled={isLoading} />
    </Box>
  )
}
