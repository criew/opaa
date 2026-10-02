import type { ChatAutoCleanupResponse } from '../../types/api'

const dateFormat = new Intl.DateTimeFormat('de-DE', { dateStyle: 'medium' })

/**
 * Wann die automatische Chat-Bereinigung des Space einen archivierten Chat löscht, als
 * Satz für die Anzeige am Chat; `null`, wenn kein Löschdatum ansteht.
 */
export function deletionDueLabel(iso: string | null | undefined): string | null {
  return iso ? `Wird am ${dateFormat.format(new Date(iso))} gelöscht` : null
}

/**
 * Der Hinweis für alle Mitglieder, wo die Chats stehen: ob und nach welchen Fristen der Space
 * inaktive Chats archiviert und löscht; `null`, wenn die Bereinigung aus ist.
 */
export function chatAutoCleanupNotice(
  cleanup: ChatAutoCleanupResponse | null | undefined,
): string | null {
  if (!cleanup?.enabled) return null
  return `In diesem Space werden inaktive Chats nach ${cleanup.archiveAfterDays} Tagen archiviert und nach weiteren ${cleanup.deleteAfterDays} Tagen im Archiv gelöscht. Angeheftete Chats bleiben.`
}
