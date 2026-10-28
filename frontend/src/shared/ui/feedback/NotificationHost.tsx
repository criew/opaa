import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import Snackbar from '@mui/material/Snackbar'
import CloseIcon from '@mui/icons-material/Close'
import type { Theme } from '@mui/material/styles'
import { useNotificationStore, type AppNotification } from '../../stores/notificationStore'

/**
 * The single render site of the global notification queue (guidelines 5.9): one popup at a time,
 * top-center, auto-dismissed after six seconds or via its close button - the next queued
 * notification follows. The timer stands still while the pointer or the focus is on the popup
 * (WCAG 2.2.1) and starts afresh once both have left. An action such as "Rückgängig" stands before the close button. Mounted
 * once in AppShell; components never render their own transient feedback inline.
 */
export default function NotificationHost() {
  const current = useNotificationStore((s) => s.queue[0] ?? null)
  const dismiss = useNotificationStore((s) => s.dismiss)

  if (current == null) return null
  // a fresh popup per notification, so a pause never carries over to the next one
  return <NotificationPopup key={current.id} current={current} dismiss={dismiss} />
}

/**
 * Below md the sticky MobileHeader (a Toolbar of `theme.mixins.toolbar` height plus a 1 px
 * border) holds the menu and the bell; the popup starts 8 px below it in every toolbar breakpoint,
 * landscape included, so it never covers them. From md on there is no header and MUI's 24 px apply.
 */
function popupTop(theme: Theme) {
  // mirrors the (nested) media queries of the toolbar mixin, minHeight turned into top
  const below = (styles: Record<string, unknown>): Record<string, unknown> =>
    Object.fromEntries(
      Object.entries(styles).flatMap(([key, value]): [string, unknown][] => {
        if (key === 'minHeight') {
          const height = typeof value === 'number' ? `${value}px` : String(value)
          return [['top', `calc(${height} + 9px)`]]
        }
        if (key.startsWith('@media') && typeof value === 'object' && value != null) {
          return [[key, below(value as Record<string, unknown>)]]
        }
        return []
      }),
    )
  return {
    ...below(theme.mixins.toolbar as Record<string, unknown>),
    [theme.breakpoints.up('md')]: { top: theme.spacing(3) },
  }
}

function NotificationPopup({
  current,
  dismiss,
}: {
  current: AppNotification
  dismiss: (id: number) => void
}) {
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)

  return (
    <Snackbar
      open
      autoHideDuration={hovered || focused ? null : 6000}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onFocus={() => setFocused(true)}
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setFocused(false)
      }}
      onClose={(_, reason) => {
        if (reason !== 'clickaway') dismiss(current.id)
      }}
      anchorOrigin={{ vertical: 'top', horizontal: 'center' }}
      sx={popupTop}
    >
      <Alert
        severity={current.severity}
        variant="outlined"
        onClose={() => dismiss(current.id)}
        action={
          current.action ? (
            <>
              <Button
                color="inherit"
                size="small"
                onClick={() => {
                  dismiss(current.id)
                  current.action?.onClick()
                }}
              >
                {current.action.label}
              </Button>
              <IconButton
                color="inherit"
                size="small"
                aria-label="Schließen"
                title="Schließen"
                onClick={() => dismiss(current.id)}
              >
                <CloseIcon fontSize="small" />
              </IconButton>
            </>
          ) : undefined
        }
        sx={{
          bgcolor: 'background.paper',
          minWidth: 0,
          '& .MuiAlert-message': { minWidth: 0, overflowWrap: 'anywhere' },
        }}
      >
        {current.message}
      </Alert>
    </Snackbar>
  )
}
