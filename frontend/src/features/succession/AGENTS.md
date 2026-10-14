# Thema succession

Nachfolge für Berechtigungen: Liste offener Nachfolgefälle in der Verwaltung, Übertragung aller Rechte einer Person oder Gruppe (`PermissionTransferDialog`) und der Hinweis auf eine offene Nachfolge an Assets und Spaces. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt `PermissionTransferDialog`, `SuccessionStateNote`, `successionAwareMessage` (Fehlertext bei offener Nachfolge), `problemOf` und `getGroupEffects` frei.
- **Spec-Tag:** `succession` (`opaa-api/src/main/openapi/succession.yaml`); dazu liest `getGroupEffects` `GET /api/v1/admin/groups/effects` aus `group`.
- **Backend-Modul:** rights (Paket `io.opaa.succession`).
- **Erlaubte Abhängigkeiten:** `principal`. Genutzt wird das Thema von `asset`, `space`, `prompt`, `library` und `group`.
