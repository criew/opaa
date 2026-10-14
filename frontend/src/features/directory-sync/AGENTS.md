# Thema directory-sync

Verzeichnisabgleich je Anmeldeanbieter: Verzeichniskonnektor einrichten und testen, Zeitplan, Probelauf und Lauf, Berichte sowie wartende Pläne bestätigen oder verwerfen. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt `PendingPlanHint` frei, den Hinweis auf wartende Pläne in der Gruppenverwaltung. Die Anbieter selbst verwaltet `identity-provider`.
- **Spec-Tag:** `directory-sync` (`opaa-api/src/main/openapi/directory-sync.yaml`).
- **Backend-Modul:** rights (Paket `io.opaa.directory`).
- **Erlaubte Abhängigkeiten:** `identity-provider`, `principal`.
- **Genutzt von:** `group`.
