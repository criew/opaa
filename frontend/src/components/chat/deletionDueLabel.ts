const dateFormat = new Intl.DateTimeFormat('de-DE', { dateStyle: 'medium' })

/**
 * Wann die automatische Chat-Bereinigung des Space einen archivierten Chat löscht, als
 * Satz für die Anzeige am Chat; `null`, wenn kein Löschdatum ansteht.
 */
export function deletionDueLabel(iso: string | null | undefined): string | null {
  return iso ? `Wird am ${dateFormat.format(new Date(iso))} gelöscht` : null
}
