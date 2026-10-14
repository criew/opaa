import type { ChatAutoCleanupResponse } from '@/shared/api/types'

const dateFormat = new Intl.DateTimeFormat('de-DE', { dateStyle: 'medium' })

/**
 * When the space's automatic chat cleanup deletes an archived chat, as a
 * sentence for display on the chat; `null` when no deletion date is due.
 */
export function deletionDueLabel(iso: string | null | undefined): string | null {
  return iso ? `Wird am ${dateFormat.format(new Date(iso))} gelöscht` : null
}

/**
 * The hint for all members about where the chats stand: whether and after which periods the
 * space archives and deletes inactive chats; `null` when the cleanup is off.
 */
export function chatAutoCleanupNotice(
  cleanup: ChatAutoCleanupResponse | null | undefined,
): string | null {
  if (!cleanup?.enabled) return null
  return `In diesem Space werden inaktive Chats nach ${cleanup.archiveAfterDays} Tagen archiviert und nach weiteren ${cleanup.deleteAfterDays} Tagen im Archiv gelöscht. Angeheftete Chats bleiben.`
}
