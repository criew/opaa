# Issue #76 — 🔵 [LOW] SQL Injection Risk in Future Migrations
- Geschlossen: 2026-08-15 (not planned)
- Labels: backend, size:S, security
- PRs: keine

**Laut Issue:** Aktuelle Liquibase-Migrationen nutzen sichere, fest eingetragene Werte, aber das Issue warnt vor hypothetischen künftigen Szenarien (Admin-UI für dynamische Statuswerte, konfigurationsgetriebene Constraints), die SQL-Injection ermöglichen könnten. Gefordert: Guidelines dokumentieren, Review-Checkliste, statische Analyse, Schulungsmaterial, Migrationsvorlage mit Sicherheitshinweisen.

**Geliefert:** Nicht umgesetzt. Laut Schließkommentar (criew, 15.08.2026, Backlog-Sichtung) benennt der Vorgang selbst keinen bestehenden Defekt — er beschreibt ein Risiko für Szenarien, die weder existieren noch geplant sind, und wäre als Dauerthema nie abschließbar. Die fachliche Substanz gilt als anderweitig bereits abgedeckt: Statuswerte laufen bereits über Java-Enums (`DocumentStatus`, `JobStatus`, `SpaceRole`, `AssetRole`), verankert über die DTO-Konvention in `AGENTS.md`/ADR-0006; Migrationen werden ohnehin im Review gelesen. Automatisierte Sicherheitsprüfung in der CI fehlt zwar tatsächlich, gilt aber als eigenes, breiteres Thema.

**Verifikation:** Kein Code-Realitätscheck nötig — die Ablehnungsbegründung ist in sich schlüssig und deckt sich mit der im Repo sichtbaren Praxis (Enum-basierte Statuswerte).

**Themen:** security, migrationen, not-planned, guideline
