import { apiClient } from '@/shared/api/api'

/**
 * Marks the "Neu hier?" hint of the quick tour as answered for the own account (idempotent). The
 * only call the quick tour makes to the server: neither the tour nor its progress is reported.
 */
export async function markQuickTourHintDone(): Promise<void> {
  await apiClient.put('/v1/me/quick-tour-hint')
}
