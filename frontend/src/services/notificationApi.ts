import type { NotificationResponse } from '../types/api'
import { apiClient as client, normalizeError } from './api'

// minimal in-app notification, deliberately narrow (see io.opaa.notification.Notification's
// Javadoc) - currently only used for "your library was associated into a mixed-audience space".
export async function getNotifications(): Promise<NotificationResponse[]> {
  try {
    const { data } = await client.get<NotificationResponse[]>('/v1/notifications')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function markNotificationRead(notificationId: string): Promise<void> {
  try {
    await client.post(`/v1/notifications/${notificationId}/read`)
  } catch (err) {
    normalizeError(err)
  }
}
