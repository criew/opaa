import { useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { alpha } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import SmartToyIcon from '@mui/icons-material/SmartToy'
import type { ChatMessage } from '../../types/chat'
import { CHAT_MAX_WIDTH } from '../../theme/theme'
import MessageBubble from './MessageBubble'
import DateSeparator from './DateSeparator'

interface MessageListProps {
  messages: ChatMessage[]
  isLoading: boolean
  /**
   * A message to open the chat at (a chat search hit): scrolled into view, marked until it loses
   * focus, and focused. An id that is not among `messages` leaves the list as it would be without.
   */
  targetMessageId?: string | null
}

function shouldShowDate(messages: ChatMessage[], index: number): boolean {
  if (index === 0) return true
  const prev = messages[index - 1].timestamp
  const curr = messages[index].timestamp
  return prev.toDateString() !== curr.toDateString()
}

export const ANSWER_ARRIVED_ANNOUNCEMENT = 'Antwort eingetroffen'

export default function MessageList({ messages, isLoading, targetMessageId }: MessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null)
  const targetRef = useRef<HTMLDivElement>(null)
  const prefersReducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)')
  const wasLoading = useRef(isLoading)
  const [announcement, setAnnouncement] = useState('')
  // The jump happens once per target; afterwards only a new message scrolls to the end again.
  const jumpedRef = useRef<{ id: string; length: number } | null>(null)
  const [highlightEndedFor, setHighlightEndedFor] = useState<string | null>(null)
  const target = messages.find((message) => message.id === targetMessageId) ?? null

  useEffect(() => {
    if (target) {
      const jumped = jumpedRef.current
      if (jumped?.id !== target.id || messages.length <= jumped.length) return
    }
    if (typeof bottomRef.current?.scrollIntoView === 'function') {
      bottomRef.current.scrollIntoView({ behavior: prefersReducedMotion ? 'auto' : 'smooth' })
    }
  }, [messages.length, prefersReducedMotion, target])

  useEffect(() => {
    const element = targetRef.current
    if (!target || !element || jumpedRef.current?.id === target.id) return
    jumpedRef.current = { id: target.id, length: messages.length }
    if (typeof element.scrollIntoView === 'function') {
      element.scrollIntoView({
        behavior: prefersReducedMotion ? 'auto' : 'smooth',
        block: 'center',
      })
    }
    element.focus({ preventScroll: true })
  }, [target, messages.length, prefersReducedMotion])

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
        {/* Level 2: this heading follows the page's h1 directly; the h6 look is only visual. */}
        <Typography variant="h6" component="h2">
          Womit kann ich Ihnen heute helfen?
        </Typography>
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
        {messages.map((msg, i) => {
          const isTarget = msg.id === target?.id
          const highlighted = isTarget && highlightEndedFor !== msg.id
          return (
            <Box key={msg.id}>
              {shouldShowDate(messages, i) && <DateSeparator date={msg.timestamp} />}
              {isTarget ? (
                <Box
                  ref={targetRef}
                  role="article"
                  aria-label={`Gefundene Nachricht: ${msg.role === 'user' ? 'Frage' : 'Antwort'}`}
                  tabIndex={-1}
                  data-message-id={msg.id}
                  data-highlighted={String(highlighted)}
                  onBlur={() => setHighlightEndedFor(msg.id)}
                  sx={(theme) => ({
                    borderRadius: '10px',
                    pt: 2,
                    mb: 2,
                    outline: highlighted ? `3px solid ${theme.palette.primary.main}` : 'none',
                    outlineOffset: '2px',
                    bgcolor: highlighted ? alpha(theme.palette.primary.main, 0.08) : 'transparent',
                    transition: prefersReducedMotion ? 'none' : 'background-color 600ms',
                  })}
                >
                  <MessageBubble message={msg} />
                </Box>
              ) : (
                <Box data-message-id={msg.id}>
                  <MessageBubble message={msg} />
                </Box>
              )}
            </Box>
          )
        })}

        {isLoading && (
          <Box
            role="status"
            aria-live="polite"
            sx={{ display: 'flex', alignItems: 'center', gap: 1.5, px: 2, mb: 2 }}
          >
            <CircularProgress size={20} />
            <Typography variant="body2" sx={{ color: 'text.secondary' }}>
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
