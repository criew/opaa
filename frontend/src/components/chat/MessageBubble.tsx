import { useState } from 'react'
import Avatar from '@mui/material/Avatar'
import Box from '@mui/material/Box'
import Collapse from '@mui/material/Collapse'
import IconButton from '@mui/material/IconButton'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import SmartToyIcon from '@mui/icons-material/SmartToy'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { ChatMessage } from '../../types/chat'
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
          maxWidth: isUser ? '80%' : '100%',
          width: isUser ? undefined : '100%',
        }}
      >
        {!isUser && (
          <Avatar sx={{ bgcolor: 'primary.main', width: 32, height: 32, mt: 0.5 }}>
            <SmartToyIcon fontSize="small" />
          </Avatar>
        )}

        <Box sx={{ minWidth: 0, flexGrow: isUser ? undefined : 1 }}>
          <Paper
            elevation={0}
            sx={{
              p: 2,
              bgcolor: isUser ? 'primary.main' : 'background.paper',
              color: isUser ? 'primary.contrastText' : 'text.primary',
              borderRadius: '16px',
              borderTopRightRadius: isUser ? '4px' : undefined,
              borderTopLeftRadius: isUser ? undefined : '4px',
            }}
          >
            {isUser ? (
              <Typography variant="body1" sx={{ whiteSpace: 'pre-wrap' }}>
                {message.content}
              </Typography>
            ) : (
              <MarkdownRenderer content={message.content} />
            )}
          </Paper>

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
                  aria-label={uncitedOpen ? 'collapse uncited sources' : 'expand uncited sources'}
                  sx={{
                    ml: 0.5,
                    transform: uncitedOpen ? 'rotate(180deg)' : 'rotate(0deg)',
                    transition: 'transform 0.2s',
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
