import { http, HttpResponse } from 'msw'
import type { NotificationResponse } from '@/shared/api/types'

/** The mock user's notifications: none, so the bell stays quiet in mock mode. */
const mockNotifications: NotificationResponse[] = []

export const notificationHandlers = [
  http.get('/api/v1/notifications', () => HttpResponse.json(mockNotifications)),

  http.post(
    '/api/v1/notifications/:notificationId/read',
    () => new HttpResponse(null, { status: 204 }),
  ),
]
