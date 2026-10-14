# Thema user

Kontenverwaltung: Kontenliste mit Filtern, Systemrolle ändern, lokale Konten anlegen, sperren, zurücksetzen und übergeben, Einstellungen der lokalen Anmeldung. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt `SEARCH_DEBOUNCE_MS` (Filterleiste der Gruppenverwaltung) und `SYSTEM_ROLE_LABEL` (Übergabeseite in `auth`) frei. Die lesende Kontensuche für Auswahlfelder gehört zu `principal`.
- **Spec-Tags:** `user` (Verwaltungsteil von `opaa-api/src/main/openapi/users.yaml`) und `local-account` (`local-accounts.yaml`).
- **Backend-Modul:** identity (Pakete `io.opaa.auth`, `io.opaa.account`).
- **Erlaubte Abhängigkeiten:** `identity-provider`, `mail`.
- **Genutzt von:** `group`, `auth`.
