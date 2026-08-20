import { useState } from 'react'
import Alert from '@mui/material/Alert'
import { alpha } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Collapse from '@mui/material/Collapse'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { ChatMessage } from '../../types/chat'
import { blue } from '../../theme/tokens'
import MarkdownRenderer from './MarkdownRenderer'
import SourceCard from './SourceCard'
import FeedbackButtons from './FeedbackButtons'

interface MessageBubbleProps {
  message: ChatMessage
}

export default function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === 'user'
  const [uncitedOpen, setUncitedOpen] = useState(false)

  const citedSources = message.sources?.filter((s) => s.cited) ?? []
  const uncitedSources = message.sources?.filter((s) => !s.cited) ?? []

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
            <MarkdownRenderer content={message.content} />
          )}

          {!isUser && message.answeredWithoutKnowledge && (
            <Alert severity="info" variant="outlined" sx={{ mt: 1 }}>
              Diese Antwort wurde ohne Wissensbasis erstellt.
            </Alert>
          )}

          {!isUser && citedSources.length > 0 && (
            <Stack direction="row" spacing={1} sx={{ mt: 1, overflowX: 'auto', pb: 0.5 }}>
              {citedSources.map((source) => (
                <SourceCard key={source.fileName} source={source} />
              ))}
            </Stack>
          )}

          {!isUser && uncitedSources.length > 0 && (
            <Box sx={{ mt: 1 }}>
              <Box
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  cursor: 'pointer',
                  userSelect: 'none',
                }}
                onClick={() => setUncitedOpen((prev) => !prev)}
              >
                <Typography variant="caption" color="text.secondary">
                  {uncitedSources.length} weitere{' '}
                  {uncitedSources.length === 1 ? 'Quelle' : 'Quellen'}
                </Typography>
                <IconButton
                  size="small"
                  aria-label={
                    uncitedOpen ? 'Weitere Quellen einklappen' : 'Weitere Quellen ausklappen'
                  }
                  sx={{
                    ml: 0.5,
                    transform: uncitedOpen ? 'rotate(180deg)' : 'rotate(0deg)',
                    transition: (theme) =>
                      theme.transitions.create('transform', {
                        duration: theme.transitions.duration.shorter,
                      }),
                  }}
                >
                  <ExpandMoreIcon fontSize="small" />
                </IconButton>
              </Box>
              <Collapse in={uncitedOpen}>
                <Stack direction="row" spacing={1} sx={{ mt: 0.5, overflowX: 'auto', pb: 0.5 }}>
                  {uncitedSources.map((source) => (
                    <SourceCard key={source.fileName} source={source} />
                  ))}
                </Stack>
              </Collapse>
            </Box>
          )}

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
