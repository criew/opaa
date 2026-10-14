/**
 * The conflict codes that can occur on any deactivation or deletion. The
 * lockout protection (`LAST_LOGIN_CAPABLE_ADMIN`) names the missing step, not only the
 * rejection (ADR-0033, Entscheidung 4).
 */
export const PROVIDER_CONFLICT_MESSAGES: Readonly<Record<string, string>> = {
  LAST_LOGIN_CAPABLE_ADMIN:
    'Danach bliebe kein anmeldefähiger Systemverwalter übrig. Richten Sie zuerst unter ' +
    'Administration → Benutzer ein lokales Systemverwalterkonto mit Passwort ein.',
  LAST_PROVIDER_ACKNOWLEDGEMENT_REQUIRED:
    'Das Backend verlangt für den letzten aktivierten Anbieter eine ausdrückliche Bestätigung. ' +
    'Bitte wiederholen Sie die Aktion und bestätigen Sie den Hinweis.',
  PROVIDER_CONNECTIONS_CONFIRMATION_REQUIRED:
    'Inzwischen kann dieser Schritt verbundene Konten von Personen betreffen. Bitte wiederholen ' +
    'Sie die Aktion und bestätigen Sie die Folgen.',
}
