import { useEffect, useRef } from 'react'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import SmartToyIcon from '@mui/icons-material/SmartToy'
import type { ChatMessage } from '../../types/chat'
import { CHAT_MAX_WIDTH } from '../../theme/theme'
import MessageBubble from './MessageBubble'
import DateSeparator from './DateSeparator'

interface MessageListProps {
  messages: ChatMessage[]
  isLoading: boolean
}

function shouldShowDate(messages: ChatMessage[], index: number): boolean {
  if (index === 0) return true
  const prev = messages[index - 1].timestamp
  const curr = messages[index].timestamp
  return prev.toDateString() !== curr.toDateString()
}

export default function MessageList({ messages, isLoading }: MessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (typeof bottomRef.current?.scrollIntoView === 'function') {
      bottomRef.current.scrollIntoView({ behavior: 'smooth' })
    }
  }, [messages.length])

  if (messages.length === 0 && !isLoading) {
    return (
      <Box
        sx={{
          flexGrow: 1,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 2,
          color: 'text.secondary',
        }}
      >
        <SmartToyIcon sx={{ fontSize: 48 }} />
        <Typography variant="h6">How can I help you today?</Typography>
        <Typography variant="body2">Ask a question about your project documents.</Typography>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', py: 2 }}>
      <Box sx={{ maxWidth: CHAT_MAX_WIDTH, mx: 'auto', pb: 2 }}>
        {messages.map((msg, i) => (
          <Box key={msg.id}>
            {shouldShowDate(messages, i) && <DateSeparator date={msg.timestamp} />}
            <MessageBubble message={msg} />
          </Box>
        ))}

        {isLoading && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, px: 2, mb: 2 }}>
            <CircularProgress size={20} />
            <Typography variant="body2" color="text.secondary">
              Thinking...
            </Typography>
          </Box>
        )}

        <div ref={bottomRef} />
      </Box>
    </Box>
  )
}
