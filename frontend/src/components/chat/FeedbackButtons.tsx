import { useState } from 'react'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Box from '@mui/material/Box'
import ThumbUpIcon from '@mui/icons-material/ThumbUp'
import ThumbUpOutlinedIcon from '@mui/icons-material/ThumbUpOutlined'
import ThumbDownIcon from '@mui/icons-material/ThumbDown'
import ThumbDownOutlinedIcon from '@mui/icons-material/ThumbDownOutlined'

type FeedbackValue = 'up' | 'down' | null

// TODO: Add messageId prop when feedback API is implemented
export default function FeedbackButtons() {
  const [feedback, setFeedback] = useState<FeedbackValue>(null)

  const handleFeedback = (value: FeedbackValue) => {
    setFeedback((prev) => (prev === value ? null : value))
  }

  return (
    <Box sx={{ display: 'flex', gap: 0.5 }}>
      <Tooltip title="Feedback coming soon">
        <IconButton
          size="small"
          aria-label="thumbs up"
          onClick={() => handleFeedback('up')}
          color={feedback === 'up' ? 'primary' : 'default'}
        >
          {feedback === 'up' ? (
            <ThumbUpIcon fontSize="small" />
          ) : (
            <ThumbUpOutlinedIcon fontSize="small" />
          )}
        </IconButton>
      </Tooltip>
      <Tooltip title="Feedback coming soon">
        <IconButton
          size="small"
          aria-label="thumbs down"
          onClick={() => handleFeedback('down')}
          color={feedback === 'down' ? 'error' : 'default'}
        >
          {feedback === 'down' ? (
            <ThumbDownIcon fontSize="small" />
          ) : (
            <ThumbDownOutlinedIcon fontSize="small" />
          )}
        </IconButton>
      </Tooltip>
    </Box>
  )
}
