import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutlineOutlined'

/** A listing that failed, in place: the reason with the signal at its mark, and „Erneut versuchen“. */
export default function LoadError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <Box role="alert" sx={{ py: 1 }}>
      <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
        <ErrorOutlineIcon aria-hidden sx={{ fontSize: 18, color: 'error.main', mt: '1px' }} />
        <Typography sx={{ fontSize: 13, overflowWrap: 'anywhere' }}>{message}</Typography>
      </Box>
      <Button size="small" variant="text" sx={{ ml: 2.5 }} onClick={onRetry}>
        Erneut versuchen
      </Button>
    </Box>
  )
}
