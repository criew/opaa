import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'

interface RetentionLoadStateProps {
  loadError: string | null
  onRetry: () => void
}

/** What a retention section shows until its setting is loaded: an error with retry, or a note. */
export default function RetentionLoadState({ loadError, onRetry }: RetentionLoadStateProps) {
  return loadError ? (
    <Alert
      severity="error"
      action={
        <Button color="inherit" size="small" onClick={onRetry}>
          Erneut laden
        </Button>
      }
    >
      {loadError}
    </Alert>
  ) : (
    <Typography role="status" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
      Frist wird geladen …
    </Typography>
  )
}
