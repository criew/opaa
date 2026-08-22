# Issue #289 — feat(backend): Organisationsgrenze auf Datenbankebene symmetrisch absichern
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:S, security
- PRs: #678 (2026-08-20)

**Laut Issue:** Die Organisationsgrenze war auf DB-Ebene nur einseitig abgesichert — die besitzende Seite (`spaces`, `groups`) über zusammengesetzte Fremdschlüssel, die Nutzerseite (`user_id` in `space_memberships`/`group_memberships`) nur über einen einfachen Fremdschlüssel auf `users(id)`, ohne Organisationsbezug. Anwendungsseitig war das über `requireUserInOrganization` geschlossen, aber nicht in der Datenbank. Gefordert: Unique-Index auf `users(id, organization_id)`, zusammengesetzte Fremdschlüssel für die Nutzerseite in beiden Tabellen, Migrationstest, Rollback.

**Geliefert:** Deutlich über den ursprünglichen Zuschnitt hinaus (laut PR "erweiterte Fassung", mit Verweis auf eine Bestandsaufnahme vom 20.08.2026, ca. 2,5 Wochen nach Issue-Erstellung): Migration 047 setzt die Organisationsgrenze für alle 18 nutzerseitigen Fremdschlüssel im Schema durch, nicht nur die zwei im Issue genannten — inklusive `spaces.owner_id`, `knowledge_libraries.owner_user_id`, `documents.uploaded_by_user_id`, diverse `actor_user_id`/`*_history`-Spalten und `chats.author_id`/`space_id`. Defensive Bestandsdatenbereinigung vor der Umstellung, vier ChangeSets, Rollback vollständig definiert. Reproduktionsnachweis erbracht (Migration ausgelassen → Test schlägt fehl).

**Verifikation:** `backend/src/main/resources/db/changelog/changes/047-bind-user-references-to-organization.yaml` existiert im Worktree, referenziert in `db.changelog-master.yaml`.

**Themen:** security, backend, datenbank, spaces, mandantentrennung
