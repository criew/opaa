# Issue #677 — fix(db): Bibliotheksreferenzen eines Chats an die Organisation binden
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S, security
- PRs: #680 (2026-08-20)

**Laut Issue:** `chat_library_references` (Migration 032) führt keine `organization_id` und verknüpft Chat und Bibliothek nur über einspaltige Fremdschlüssel — die Mandantengrenze (ADR-0008) wurde damit nur anwendungsseitig gehalten, nicht auf DB-Ebene. Gefordert: `organization_id` ergänzen, zusammengesetzte Fremdschlüssel gegen `chats(id, organization_id)`/`knowledge_libraries(id, organization_id)`, Migrationstest nach dem Muster von Migration 046, Bestandsdatenbereinigung, Rollback.

**Geliefert:** Migration 048 wie gefordert, mit einer im Issue nicht vorgesehenen, aber sauber begründeten technischen Ergänzung: `organization_id` wird per BEFORE-INSERT-Trigger aus der Chat-Zeile abgeleitet (statt reiner NOT-NULL-Spalte), weil Hibernates `@ElementCollection`-Insert die Spalte sonst nicht befüllt hätte — im PR mit dem entsprechenden Testfehler belegt. Anwendungscode (`ChatService#requireReadableLibraries`) wurde geprüft und war bereits korrekt, kein Umbau nötig.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/048-bind-chat-library-references-to-organization.yaml` existiert im Worktree.

**Themen:** datenbank, mandantengrenze, migration, security, chats
