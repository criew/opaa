import { useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import visuallyHidden from '@mui/utils/visuallyHidden'
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

export const ANSWER_ARRIVED_ANNOUNCEMENT = 'Antwort eingetroffen'

export default function MessageList({ messages, isLoading }: MessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null)
  const prefersReducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)')
  const wasLoading = useRef(isLoading)
  const [announcement, setAnnouncement] = useState('')

  useEffect(() => {
    if (typeof bottomRef.current?.scrollIntoView === 'function') {
      bottomRef.current.scrollIntoView({ behavior: prefersReducedMotion ? 'auto' : 'smooth' })
    }
  }, [messages.length, prefersReducedMotion])

  // Answers arrive in one piece, so the only cue for screen reader users is this live region:
  // announce the loading -> done transition, then clear it so the next answer is read out again.
  useEffect(() => {
    if (wasLoading.current && !isLoading) {
      setAnnouncement(ANSWER_ARRIVED_ANNOUNCEMENT)
      const timer = setTimeout(() => setAnnouncement(''), 1000)
      wasLoading.current = isLoading
      return () => clearTimeout(timer)
    }
    wasLoading.current = isLoading
  }, [isLoading])

  const liveRegion = (
    <Box component="div" role="status" aria-live="polite" sx={visuallyHidden}>
      {announcement}
    </Box>
  )

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
        <Typography variant="h6">Womit kann ich Ihnen heute helfen?</Typography>
        <Typography variant="body2">Stellen Sie eine Frage zu Ihren Projektdokumenten.</Typography>
        {liveRegion}
      </Box>
    )
  }

  return (
    // data-testid: the chat header (#658) repeats the first question as the fallback title, so
    // the E2E suite anchors history assertions here instead of on <main> as a whole.
    // position: 'relative' (#749): the live region below (visuallyHidden -> position: absolute)
    // otherwise has no positioned ancestor, so its containing block is the viewport rather than
    // this scroll container - its "static position" grows with the message count and, without
    // this positioning context, escapes this box's overflow clipping. That inflates
    // document.documentElement.scrollHeight, producing an outer page scrollbar in addition to
    // this list's own, and pushes the app shell (footer, sidebar) taller than the viewport.
    <Box
      data-testid="message-list"
      sx={{ flexGrow: 1, overflowY: 'auto', py: 2, position: 'relative' }}
    >
      <Box sx={{ maxWidth: CHAT_MAX_WIDTH, mx: 'auto', pb: 2 }}>
        {messages.map((msg, i) => (
          <Box key={msg.id}>
            {shouldShowDate(messages, i) && <DateSeparator date={msg.timestamp} />}
            <MessageBubble message={msg} />
          </Box>
        ))}

        {isLoading && (
          <Box
            role="status"
            aria-live="polite"
            sx={{ display: 'flex', alignItems: 'center', gap: 1.5, px: 2, mb: 2 }}
          >
            <CircularProgress size={20} />
            <Typography variant="body2" color="text.secondary">
              Denkt nach …
            </Typography>
          </Box>
        )}

        {liveRegion}
        <div ref={bottomRef} />
      </Box>
    </Box>
  )
}
