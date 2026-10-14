# Thema principal

Personen- und Gruppenauswahl: Auswahlfelder für Konten, Gruppen und „Alle Konten“, Gruppenmitglieder-Offenlegung, Beschriftung von Gruppenherkunft und -alter sowie die lesenden Such- und Nachschlage-Aufrufe dahinter. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** keine Seiten, kein `routes.ts`. `index.ts` gibt die Auswahlbausteine, die Auswahl-Helfer (`subjectSelection`), `useMyGroups`, `getUsers`, die Gruppen-Nachschlagefunktionen und die Herkunftsbeschriftungen frei. Gruppen anlegen und verwalten gehört zu `group`, Kontenverwaltung zu `user`.
- **Spec-Tags:** lesende Operationen aus `user` (`GET /api/v1/users`, `GET /api/v1/admin/users`) und `group` (`GET /api/v1/groups/selectable`, `GET /api/v1/me/groups`); eigene Spec-Datei gibt es nicht.
- **Backend-Modul:** identity, rights.
- **Erlaubte Abhängigkeiten:** nur `shared`. Genutzt wird das Thema von `space`, `asset`, `library`, `prompt`, `group`, `succession`, `capability` und `directory-sync`.
