import { create } from 'zustand'

export type NotificationSeverity = 'success' | 'info' | 'warning' | 'error'

export interface AppNotification {
  id: number
  message: string
  severity: NotificationSeverity
}

interface NotificationState {
  /** FIFO queue - NotificationHost shows the head and moves on as entries are dismissed. */
  queue: AppNotification[]
  notify: (message: string, severity?: NotificationSeverity) => void
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
  notify: (message, severity = 'info') =>
    set((state) => ({
      queue: [...state.queue, { id: nextNotificationId++, message, severity }],
    })),
  dismiss: (id) => set((state) => ({ queue: state.queue.filter((n) => n.id !== id) })),
  reset: () => set({ queue: [] }),
}))

/** Imperative entry point for hooks and stores outside the component tree. */
export function notify(message: string, severity: NotificationSeverity = 'info') {
  useNotificationStore.getState().notify(message, severity)
}
