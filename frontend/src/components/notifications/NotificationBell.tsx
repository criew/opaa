import { useEffect, useState } from 'react'
import Badge from '@mui/material/Badge'
import Box from '@mui/material/Box'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Typography from '@mui/material/Typography'
import NotificationsIcon from '@mui/icons-material/Notifications'
import type { NotificationResponse } from '../../types/api'
import { getNotifications, markNotificationRead } from '../../services/api'
import { useAuthStore } from '../../stores/authStore'

function formatCreatedAt(value: string): string {
  return new Date(value).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
}

/**
 * Minimal in-app notification display (#203) - a bell with an unread badge and a dropdown list.
 * Deliberately narrow: no push, no polling beyond mount/open, no grouping - just enough that a
 * library owner learns their library was associated into a mixed-audience space without having to
 * check a list themselves (docs/features/spaces-and-assets.md#assets-in-einen-space-assoziieren).
 */
export default function NotificationBell() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const [notifications, setNotifications] = useState<NotificationResponse[]>([])
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null)

  useEffect(() => {
    if (!isAuthenticated) return
    void getNotifications()
      .then(setNotifications)
      .catch(() => setNotifications([]))
  }, [isAuthenticated])

  if (!isAuthenticated) return null

  const unreadCount = notifications.filter((n) => !n.readAt).length

  async function handleOpen(event: React.MouseEvent<HTMLElement>) {
    setAnchorEl(event.currentTarget)
    try {
      setNotifications(await getNotifications())
    } catch {
      // Der zuletzt geladene Stand bleibt sichtbar; ein Fehler hier ist nicht kritisch genug für
      // eine eigene Fehlermeldung.
    }
  }

  async function handleItemClick(notification: NotificationResponse) {
    if (!notification.readAt) {
      try {
        await markNotificationRead(notification.id)
        setNotifications((prev) =>
          prev.map((n) =>
            n.id === notification.id ? { ...n, readAt: new Date().toISOString() } : n,
          ),
        )
      } catch {
        // Bleibt ungelesen, falls das Markieren fehlschlägt - kein Blocker für die Anzeige.
      }
    }
  }

  return (
    <>
      <IconButton
        onClick={(event) => void handleOpen(event)}
        aria-label={
          unreadCount > 0
            ? `Benachrichtigungen, ${unreadCount} ungelesen`
            : 'Benachrichtigungen, keine ungelesenen'
        }
        aria-haspopup="menu"
        aria-expanded={anchorEl ? 'true' : undefined}
      >
        <Badge badgeContent={unreadCount} color="error">
          <NotificationsIcon />
        </Badge>
      </IconButton>
      <Menu
        anchorEl={anchorEl}
        open={Boolean(anchorEl)}
        onClose={() => setAnchorEl(null)}
        slotProps={{ paper: { sx: { minWidth: 320, maxWidth: 420 } } }}
      >
        {notifications.length === 0 ? (
          <MenuItem disabled>Keine Benachrichtigungen</MenuItem>
        ) : (
          notifications.map((notification, index) => [
            index > 0 && <Divider key={`${notification.id}-divider`} />,
            <MenuItem
              key={notification.id}
              onClick={() => void handleItemClick(notification)}
              sx={{ whiteSpace: 'normal', alignItems: 'flex-start' }}
            >
              <Box sx={{ py: 0.5 }}>
                <Typography sx={{ fontWeight: notification.readAt ? 400 : 600, fontSize: 13.5 }}>
                  {notification.title}
                </Typography>
                {notification.body && (
                  <Typography variant="body2" color="text.secondary">
                    {notification.body}
                  </Typography>
                )}
                <Typography variant="caption" color="text.secondary">
                  {formatCreatedAt(notification.createdAt)}
                </Typography>
              </Box>
            </MenuItem>,
          ])
        )}
      </Menu>
    </>
  )
}
