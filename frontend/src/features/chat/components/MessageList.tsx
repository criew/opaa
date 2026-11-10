import { useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { alpha } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import SmartToyIcon from '@mui/icons-material/SmartToy'
import type { ChatMessage, LiveAgentRun } from '../types/chat'
import type { AgentRun } from '@/shared/api/types'
import { CHAT_MAX_WIDTH } from '@/shared/theme/theme'
import { AiNotice } from './AiNotice'
import MessageBubble from './MessageBubble'
import DateSeparator from './DateSeparator'
import ProvisionalAnswer, {
  AGENT_PROVISIONAL_ANSWER_LABEL,
  PROVISIONAL_ANSWER_LABEL,
} from './ProvisionalAnswer'
import { stepActivity, stepText } from './agentStepText'
import { useCopyAnnouncement } from './copyAnnouncer'
import { iconSize } from '@/shared/theme/tokens'

interface MessageListProps {
  messages: ChatMessage[]
  isLoading: boolean
  /**
   * A message to open the chat at (a chat search hit): scrolled into view, marked until it loses
   * focus, and focused. An id that is not among `messages` leaves the list as it would be without.
   */
  targetMessageId?: string | null
  /** The provisional answer while the model writes it, null before its first part. */
  streamingAnswer?: string | null
  /** The agent run this answer waits for while it works, null for an ordinary answer. */
  streamingRun?: LiveAgentRun | null
}

export const THINKING_STATUS = 'Denkt nach …'
export const LIMIT_REACHED_STATUS = 'Grenze erreicht, antwortet mit dem Gefundenen …'

/**
 * The status line of an agent run: once it ended, that its answer waits for the check of its
 * Belege; before, its limit or its running step - the last one stays until the next begins, so
 * each step is announced once - or null for "Denkt nach …".
 */
function runStatus(run: LiveAgentRun | null): string | null {
  if (!run) return null
  if (run.ended) return AGENT_PROVISIONAL_ANSWER_LABEL
  if (run.limitReached) return LIMIT_REACHED_STATUS
  const current = run.steps.find((step) => !step.status) ?? run.steps.at(-1)
  return current ? stepActivity(current) : null
}

/** The steps of an ended run in the form the kept answer shows them. */
function keptForm(run: LiveAgentRun): AgentRun {
  return {
    runId: run.runId ?? '',
    endState: run.endState ?? 'COMPLETED',
    steps: run.steps.map(({ tool, detail, hits, status }) => ({
      tool,
      detail,
      hits,
      status: status ?? 'DONE',
    })),
  }
}

function shouldShowDate(messages: ChatMessage[], index: number): boolean {
  if (index === 0) return true
  const prev = messages[index - 1].timestamp
  const curr = messages[index].timestamp
  return prev.toDateString() !== curr.toDateString()
}

export const ANSWER_ARRIVED_ANNOUNCEMENT = 'Antwort eingetroffen'

export default function MessageList({
  messages,
  isLoading,
  targetMessageId,
  streamingAnswer = null,
  streamingRun = null,
}: MessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null)
  const targetRef = useRef<HTMLDivElement>(null)
  const prefersReducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)')
  const wasLoading = useRef(isLoading)
  const [announcement, setAnnouncement] = useState('')
  const copyAnnouncement = useCopyAnnouncement()
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

  // The growing provisional answer stays in view, without the smooth scroll of a new message.
  const streaming = streamingAnswer !== null
  useEffect(() => {
    if (!streaming || target) return
    if (typeof bottomRef.current?.scrollIntoView === 'function') {
      bottomRef.current.scrollIntoView({ block: 'end' })
    }
  }, [streaming, streamingAnswer, target])

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

  // The streamed text is no live region, so the only cue for screen reader users is this one:
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
    <>
      <Box component="div" role="status" aria-live="polite" sx={visuallyHidden}>
        {announcement}
      </Box>
      {/* The copy buttons of every message report here - one region for the whole history. The
          invisible alternating suffix makes the same sentence twice in a row a change again. */}
      <Box component="div" aria-live="polite" data-testid="copy-announcement" sx={visuallyHidden}>
        {copyAnnouncement.text}
        {copyAnnouncement.sequence % 2 === 1 ? '​' : ''}
      </Box>
    </>
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
        <SmartToyIcon sx={{ fontSize: iconSize.xl }} />
        {/* Level 2: this heading follows the page's h1 directly; the h6 look is only visual. */}
        <Typography variant="h6" component="h2">
          Womit kann ich Ihnen heute helfen?
        </Typography>
        <Typography variant="body2">Stellen Sie eine Frage zu Ihren Projektdokumenten.</Typography>
        <AiNotice variant="prominent" />
        {liveRegion}
      </Box>
    )
  }

  return (
    // data-testid: the chat header repeats the first question as the fallback title, so
    // the E2E suite anchors history assertions here instead of on <main> as a whole.
    // position: 'relative': the live region below (visuallyHidden -> position: absolute) takes
    // this scroll container as its containing block, so its static position, which grows with
    // the message count, stays inside this box's own overflow clipping.
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

        {/* One status from "Denkt nach …" to the end of the answer: its text changes with each
            step of an agent and once the first part arrives, which a screen reader announces; a
            region inserted with its text would not be. */}
        {isLoading && (
          <Box
            role="status"
            aria-live="polite"
            sx={{ display: 'flex', alignItems: 'center', gap: 1.5, px: 2, mb: streaming ? 1 : 2 }}
          >
            <CircularProgress size={streaming ? 16 : 20} aria-hidden />
            <Typography variant="body2" noWrap sx={{ color: 'text.secondary', minWidth: 0 }}>
              {runStatus(streamingRun) ?? (streaming ? PROVISIONAL_ANSWER_LABEL : THINKING_STATUS)}
            </Typography>
          </Box>
        )}
        {/* The steps so far stay readable at the reader's pace below the status line; they are no
            live region, the status line announces each one. Once the run ended they move under
            the provisional answer in the form the kept answer shows them, so nothing shifts when
            it arrives. */}
        {isLoading && streamingRun && !streamingRun.ended && streamingRun.steps.length > 0 && (
          <Box
            component="ol"
            aria-label="Schritte"
            data-testid="live-agent-steps"
            sx={{ m: 0, mb: 1, pl: 7, pr: 2, color: 'text.secondary', typography: 'body2' }}
          >
            {streamingRun.steps.map((step) => (
              <li key={step.index}>{stepText(step)}</li>
            ))}
          </Box>
        )}
        {isLoading && (streamingAnswer !== null || streamingRun?.ended) && (
          <ProvisionalAnswer
            text={streamingAnswer ?? ''}
            run={streamingRun?.ended ? keptForm(streamingRun) : undefined}
          />
        )}

        {liveRegion}
        <div ref={bottomRef} />
      </Box>
    </Box>
  )
}
