# Thema external-access

Externer Zugriff für KI-Werkzeuge (MCP): Einstellungen und Token-Übersicht in der Verwaltung, freigegebene Bibliotheken, eigene Zugriffstoken mit einmaliger Anzeige des Werts in den persönlichen Einstellungen. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Das Thema hat keine eigenen Seiten. `index.ts` gibt `ExternalToolsTab` (Verbindungsverwaltung in `app`), `OwnExternalAccessTokensSection` (Einstellungen in `app`) und `updateLibraryExternalAccess` (Freigabe einer Bibliothek in `library`) frei.
- **Spec-Tag:** `external-access` (`opaa-api/src/main/openapi/external-access.yaml`).
- **Backend-Modul:** external (Paket `io.opaa.externalaccess`).
- **Erlaubte Abhängigkeiten:** `asset`.
- **Genutzt von:** `library`, `app`.
