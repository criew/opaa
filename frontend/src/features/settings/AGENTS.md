# Thema settings

Export und Import der Einstellungen der Installation, mit Vorschau der Änderungen. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt nichts frei. Die einzelnen Einstellungen pflegen die Themen ihrer Bereiche; dieses Thema liest und schreibt sie nur als ganzes Dokument.
- **Spec-Tag:** `settings` (`opaa-api/src/main/openapi/settings.yaml`).
- **Backend-Modul:** identity (Paket `io.opaa.settings`).
- **Erlaubte Abhängigkeiten:** keine.
- **Genutzt von:** kein anderes Thema.
