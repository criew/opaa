import { create } from 'zustand'

export type NotificationSeverity = 'success' | 'info' | 'warning' | 'error'

/** One button beside the message, such as "Rückgängig"; pressing it also dismisses the popup. */
export interface NotificationAction {
  label: string
  onClick: () => void
}

export interface AppNotification {
  id: number
  message: string
  severity: NotificationSeverity
  action?: NotificationAction
}

interface NotificationState {
  /** FIFO queue - NotificationHost shows the head and moves on as entries are dismissed. */
  queue: AppNotification[]
  notify: (message: string, severity?: NotificationSeverity, action?: NotificationAction) => void
  dismiss: (id: number) => void
  reset: () => void
}

let nextNotificationId = 1

/**
 * Global popup notifications (guidelines 5.9): transient feedback on one-off actions - a failed
 * download, a started download, a finished background step - surfaces here and is rendered once
 * by {@link ../components/NotificationHost}, never as an inline alert pushed above unrelated
 * content.
 */
export const useNotificationStore = create<NotificationState>((set) => ({
  queue: [],
  notify: (message, severity = 'info', action) =>
    set((state) => ({
      queue: [...state.queue, { id: nextNotificationId++, message, severity, action }],
    })),
  dismiss: (id) => set((state) => ({ queue: state.queue.filter((n) => n.id !== id) })),
  reset: () => set({ queue: [] }),
}))

/** Imperative entry point for hooks and stores outside the component tree. */
export function notify(
  message: string,
  severity: NotificationSeverity = 'info',
  action?: NotificationAction,
) {
  useNotificationStore.getState().notify(message, severity, action)
}
