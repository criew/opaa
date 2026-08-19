import { useEffect } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import MessageList from '../components/chat/MessageList'
import ChatInput from '../components/chat/ChatInput'
import { useChatStore } from '../stores/chatStore'
import { useSpaceStore } from '../stores/spaceStore'

export default function ChatPage() {
  const messages = useChatStore((s) => s.messages)
  const isLoading = useChatStore((s) => s.isLoading)
  const error = useChatStore((s) => s.error)
  const sendMessage = useChatStore((s) => s.sendMessage)
  const spaces = useSpaceStore((s) => s.spaces)
  const chatFilterSpaceIds = useSpaceStore((s) => s.chatFilterSpaceIds)
  const setChatFilterSpaceIds = useSpaceStore((s) => s.setChatFilterSpaceIds)
  const loadSpaces = useSpaceStore((s) => s.loadSpaces)

  useEffect(() => {
    if (spaces.length === 0) {
      void loadSpaces()
    }
  }, [loadSpaces, spaces.length])

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', flexGrow: 1, minHeight: 0 }}>
      {error && (
        <Alert severity="error" sx={{ m: 2 }}>
          {error}
        </Alert>
      )}
      <MessageList messages={messages} isLoading={isLoading} />
      <ChatInput
        onSend={(message) => sendMessage(message)}
        disabled={isLoading}
        spaces={spaces}
        selectedSpaceIds={chatFilterSpaceIds}
        onSpaceFilterChange={setChatFilterSpaceIds}
      />
    </Box>
  )
}
