# Thema connection

Verbindungen zu Quellsystemen: Zugänge (Verbindungsprofile) mit Anmeldung und Änderungsvorschau, Quellarten mit Zugriff je Konnektortyp, ruhende Quellverbindungen, globaler Proxy und die verbundenen Konten in den persönlichen Einstellungen. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Das Thema hat keine eigenen Seiten; die Reiter und Abschnitte bettet `app` über `index.ts` in die Verbindungsverwaltung und die Einstellungen ein. Für `library` gibt `index.ts` die Profilauswahl (`useConnectionProfileOptions`), die Quellarten (`useSourceTypes`, `SOURCE_TYPE_DIRECTORY`, `sourceTypeIcon`), die Kontoanbindung (`startSourceAuthorization`, `completeConnectionAuthorization`) und die Zustimmungsbausteine frei.
- **Spec-Tags:** `connection-profile`, `connected-account`, `outbound-proxy` (`opaa-api/src/main/openapi/connections.yaml`); dazu liest `sourceTypeApi` `GET /api/v1/source-types` aus `library`.
- **Backend-Modul:** connections (Paket `io.opaa.connection`).
- **Erlaubte Abhängigkeiten:** `capability`, `audit`. Genutzt wird das Thema von `library`.
