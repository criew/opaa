/**
 * Die beiden Konfliktcodes, die an jedem Deaktivieren oder Löschen auftreten können. Der
 * Aussperrschutz (`LAST_LOGIN_CAPABLE_ADMIN`) nennt den fehlenden Schritt, nicht nur die
 * Ablehnung (ADR-0033, Entscheidung 4).
 */
export const PROVIDER_CONFLICT_MESSAGES: Readonly<Record<string, string>> = {
  LAST_LOGIN_CAPABLE_ADMIN:
    'Danach bliebe kein anmeldefähiger Systemverwalter übrig. Richten Sie zuerst unter ' +
    'Administration → Benutzer ein lokales Systemverwalterkonto mit Passwort ein.',
  LAST_PROVIDER_ACKNOWLEDGEMENT_REQUIRED:
    'Das Backend verlangt für den letzten aktivierten Anbieter eine ausdrückliche Bestätigung. ' +
    'Bitte wiederholen Sie die Aktion und bestätigen Sie den Hinweis.',
}
