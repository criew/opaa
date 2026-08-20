import Alert from '@mui/material/Alert'
import Snackbar from '@mui/material/Snackbar'
import { useIndexingStore } from '../../stores/indexingStore'

export default function IndexingSnackbar() {
  const snackbar = useIndexingStore((s) => s.snackbar)
  const closeSnackbar = useIndexingStore((s) => s.closeSnackbar)

  return (
    <Snackbar
      open={snackbar.open}
      autoHideDuration={6000}
      onClose={closeSnackbar}
      anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
    >
      {/* A polite status, not an interrupting alert - screen readers read it after the current
          utterance. */}
      <Alert onClose={closeSnackbar} severity={snackbar.severity} variant="filled" role="status">
        {snackbar.message}
      </Alert>
    </Snackbar>
  )
}
