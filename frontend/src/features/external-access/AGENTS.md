# Thema external-access

Externer Zugriff für KI-Werkzeuge (MCP): Einstellungen und Token-Übersicht in der Verwaltung, freigegebene Bibliotheken, eigene Zugriffstoken mit einmaliger Anzeige des Werts in den persönlichen Einstellungen, Dienstkonten mit ihren Tokens und der Zustimmung je Bibliothek. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Das Thema hat keine eigenen Seiten. `index.ts` gibt `ExternalToolsTab` und `ServiceAccountsTab` (Verbindungsverwaltung in `app`), `OwnExternalAccessTokensSection` (Einstellungen in `app`), `updateLibraryExternalAccess` und `LibraryServiceAccountsSection` (Freigabe einer Bibliothek und Dienstkonten mit Zugriff in `library`) frei.
- **Spec-Tag:** `external-access` (`opaa-api/src/main/openapi/external-access.yaml`).
- **Backend-Modul:** external (Paket `io.opaa.externalaccess`).
- **Erlaubte Abhängigkeiten:** `asset`, `principal` (Personensuche `UserPicker` für die verantwortliche Person eines Dienstkontos).
- **Genutzt von:** `library`, `app`.
