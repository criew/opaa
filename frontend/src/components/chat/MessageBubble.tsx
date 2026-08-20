import { useMemo } from 'react'
import Alert from '@mui/material/Alert'
import { alpha } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { ChatMessage } from '../../types/chat'
import { blue } from '../../theme/tokens'
import { buildCitationIndex } from './citations'
import MarkdownRenderer from './MarkdownRenderer'
import SourceFootnotes from './SourceFootnotes'
import FeedbackButtons from './FeedbackButtons'

interface MessageBubbleProps {
  message: ChatMessage
}

export default function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === 'user'

  // Mockup 1a (#590): the answer's citation markers resolve to footnote numbers, rendered as
  // superscripts in the text and as the Fundstellen block below it.
  const citations = useMemo(
    () => buildCitationIndex(message.content, message.sources),
    [message.content, message.sources],
  )

  return (
    <Box
      sx={{
        display: 'flex',
        justifyContent: isUser ? 'flex-end' : 'flex-start',
        mb: 2,
        px: 2,
      }}
    >
      <Box
        sx={{
          display: 'flex',
          gap: 1.5,
          maxWidth: isUser ? '78%' : '100%',
          width: isUser ? undefined : '100%',
        }}
      >
        <Box sx={{ minWidth: 0, flexGrow: isUser ? undefined : 1 }}>
          {/* Mockup 1a (#658): questions sit in a quiet blue-50 bubble with navy text; answers
              are plain running text without an avatar or a bubble around them. */}
          {isUser ? (
            <Box
              sx={(theme) => ({
                px: 2,
                py: 1.5,
                borderRadius: '10px',
                border: 1,
                bgcolor:
                  theme.palette.mode === 'dark'
                    ? alpha(theme.palette.primary.main, 0.16)
                    : blue[50],
                borderColor:
                  theme.palette.mode === 'dark'
                    ? alpha(theme.palette.primary.main, 0.32)
                    : blue[100],
                color: 'text.primary',
              })}
            >
              <Typography variant="body1" sx={{ whiteSpace: 'pre-wrap' }}>
                {message.content}
              </Typography>
            </Box>
          ) : (
            <MarkdownRenderer
              content={message.content}
              citations={citations}
              messageId={message.id}
            />
          )}

          {!isUser && message.answeredWithoutKnowledge && (
            <Alert severity="info" variant="outlined" sx={{ mt: 1 }}>
              Diese Antwort wurde ohne Wissensbasis erstellt.
            </Alert>
          )}

          {!isUser && <SourceFootnotes messageId={message.id} citations={citations} />}

          {!isUser && (
            <Box sx={{ mt: 0.5 }}>
              <FeedbackButtons />
            </Box>
          )}
        </Box>
      </Box>
    </Box>
  )
}
