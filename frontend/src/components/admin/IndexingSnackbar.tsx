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
      <Alert onClose={closeSnackbar} severity={snackbar.severity} variant="filled">
        {snackbar.message}
      </Alert>
    </Snackbar>
  )
}
