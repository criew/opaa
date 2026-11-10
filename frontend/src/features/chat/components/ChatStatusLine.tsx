import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'

interface ChatStatusLineProps {
  /** A short sentence on what the answer is doing right now ("Denkt nach …"). */
  text: string
}

/**
 * The status line of the running answer: a spinner with one short sentence. It is the only place
 * where a spinner carries visible text (guidelines 5.7) - it informs, it is not a loading state.
 * One mounted line from "Denkt nach …" to the end of the answer: a screen reader announces each
 * change of its text, which a region inserted together with its text would not be. Spinner size
 * and spacing do not change with the text, so nothing below it moves.
 */
export default function ChatStatusLine({ text }: ChatStatusLineProps) {
  return (
    <Box
      role="status"
      aria-live="polite"
      data-testid="chat-status-line"
      sx={{ display: 'flex', alignItems: 'center', gap: 1.5, px: 2, mb: 1 }}
    >
      <CircularProgress size={16} aria-hidden />
      <Typography variant="body2" noWrap sx={{ color: 'text.secondary', minWidth: 0 }}>
        {text}
      </Typography>
    </Box>
  )
}
