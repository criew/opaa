# Issue #391 — feat(audit): Protokollablage und Protokollsatz, nur anfügend
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #428 (2026-08-17)

**Laut Issue:** Aus #355 abgeleitet, Fundament des Audit-Loggings. Ablage mit vollständigem Protokollsatz (16 Felder, ohne Netzadresse/Gerät/Standort), `event_type` als geschlossene Liste, nur anfügend auf DB-Ebene (Anwendungskonto ohne UPDATE/DELETE/TRUNCATE), Unterteilung nach Monaten, getrennte Pseudonymzuordnung, Transaktionsverhalten (Rollback des Auslösers rollt auch den Protokolleintrag zurück).

**Geliefert:** Wie beschrieben. `audit_log` monatlich partitioniert, `event_type`/`object_type`/`actor_kind`/`subject_kind`/`outcome` als geschlossene Listen (Java-Enum plus DB-Check-Constraint, per Test synchron gehalten), `audit_actor_pseudonyms` als getrennte Zuordnungstabelle mit `ON DELETE CASCADE`, `AuditLogService.record` läuft bewusst ohne eigene Transaktion in der des Aufrufers mit. Bekannte, offen dokumentierte Einschränkung: Das mitgelieferte Compose-Setup betreibt Postgres als Superuser, der Schreibschutz ist dort inert — als Folge-Issue #426 festgehalten. Review-Nachtrag (Runde 2) ergänzte Eigentümertrennung auf eine dedizierte `opaa_audit_owner`-Rolle (ADR-0015). Ausdrücklich vermerkter Merge-Konflikt-Hinweis zu parallelem PR #427 (gleiche Dateien, kollidierende ADR-Nummer 0015) — Nachbesserungsbedarf beim Merge-Reihenfolge, der PR-Body dokumentiert den Konflikt statt ihn zu verschweigen.

**Verifikation:** Das Audit-Paket existiert vollständig im Worktree unter `backend/src/main/java/io/opaa/audit/` (u. a. `AuditLogService.java`, `AuditActorPseudonymService.java`, `AuditEventType.java`).

**Themen:** audit, protokoll, security, backend, revisionssicherheit
