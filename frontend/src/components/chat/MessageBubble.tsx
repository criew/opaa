import Avatar from '@mui/material/Avatar'
import Box from '@mui/material/Box'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import SmartToyIcon from '@mui/icons-material/SmartToy'
import type { ChatMessage } from '../../types/chat'
import SourceCard from './SourceCard'
import FeedbackButtons from './FeedbackButtons'

interface MessageBubbleProps {
  message: ChatMessage
}

export default function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === 'user'

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
            <Typography variant="body1" sx={{ whiteSpace: 'pre-wrap' }}>
              {message.content}
            </Typography>
          </Paper>

          {!isUser && message.sources && message.sources.length > 0 && (
            <Stack direction="row" spacing={1} sx={{ mt: 1, overflowX: 'auto', pb: 0.5 }}>
              {message.sources.map((source) => (
                <SourceCard key={source.fileName} source={source} />
              ))}
            </Stack>
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
