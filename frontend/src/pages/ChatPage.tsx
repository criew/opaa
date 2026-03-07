import { useEffect } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import MessageList from '../components/chat/MessageList'
import ChatInput from '../components/chat/ChatInput'
import { useChatStore } from '../stores/chatStore'
import { useWorkspaceStore } from '../stores/workspaceStore'

export default function ChatPage() {
  const messages = useChatStore((s) => s.messages)
  const isLoading = useChatStore((s) => s.isLoading)
  const error = useChatStore((s) => s.error)
  const sendMessage = useChatStore((s) => s.sendMessage)
  const workspaces = useWorkspaceStore((s) => s.workspaces)
  const chatFilterWorkspaceId = useWorkspaceStore((s) => s.chatFilterWorkspaceId)
  const setChatFilterWorkspaceId = useWorkspaceStore((s) => s.setChatFilterWorkspaceId)
  const loadWorkspaces = useWorkspaceStore((s) => s.loadWorkspaces)

  useEffect(() => {
    if (workspaces.length === 0) {
      void loadWorkspaces()
    }
  }, [loadWorkspaces, workspaces.length])

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', flexGrow: 1, minHeight: 0 }}>
      {error && (
        <Alert severity="error" sx={{ m: 2 }}>
          {error}
        </Alert>
      )}
      <MessageList messages={messages} isLoading={isLoading} />
      <ChatInput
        onSend={(message) =>
          sendMessage(message, chatFilterWorkspaceId ? [chatFilterWorkspaceId] : undefined)
        }
        disabled={isLoading}
        workspaces={workspaces}
        selectedWorkspaceId={chatFilterWorkspaceId}
        onWorkspaceFilterChange={setChatFilterWorkspaceId}
      />
    </Box>
  )
}
