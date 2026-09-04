import Alert from '@mui/material/Alert'
import Snackbar from '@mui/material/Snackbar'
import { useNotificationStore } from '../stores/notificationStore'

/**
 * The single render site of the global notification queue (guidelines 5.9): one popup at a time,
 * bottom-center, auto-dismissed after six seconds or via its close button - the next queued
 * notification follows. Mounted once in AppShell; components never render their own transient
 * feedback inline.
 */
export default function NotificationHost() {
  const current = useNotificationStore((s) => s.queue[0] ?? null)
  const dismiss = useNotificationStore((s) => s.dismiss)

  if (current == null) return null

  return (
    <Snackbar
      key={current.id}
      open
      autoHideDuration={6000}
      onClose={(_, reason) => {
        if (reason !== 'clickaway') dismiss(current.id)
      }}
      anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
    >
      <Alert
        severity={current.severity}
        variant="outlined"
        onClose={() => dismiss(current.id)}
        sx={{ bgcolor: 'background.paper' }}
      >
        {current.message}
      </Alert>
    </Snackbar>
  )
}
