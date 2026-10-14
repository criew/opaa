# Thema group

Gruppen: Gruppenverwaltung mit Mitgliedern, Verantwortlichen und Deaktivierung, Arbeitsliste der Gruppen eines Anmeldeanbieters vor dessen Löschung, Gruppe anlegen und „Meine Gruppen“ in den persönlichen Einstellungen. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt `MyGroupsSection` (Einstellungsseite in `app`) und `useGroupStore` (Abmeldetest der Sitzungsstores) frei. Die lesende Gruppensuche für Auswahlfelder und `GroupPicker` gehören zu `principal`.
- **Spec-Tag:** `group` (`opaa-api/src/main/openapi/groups.yaml`).
- **Backend-Modul:** rights (Paket `io.opaa.group`).
- **Erlaubte Abhängigkeiten:** `principal`, `succession`, `identity-provider`, `directory-sync`, `user`. Kein anderes Thema hängt von `group` ab.
