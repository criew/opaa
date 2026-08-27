# Issue #845 — docs: ADR Single-Instance-Betrieb — verstreute Annahmen bündeln
- Geschlossen: 2026-08-24 (completed)
- Labels: documentation, backend, size:S
- PRs: #859 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 2 (Befund B9). Das Backend trifft an mindestens acht Stellen unabhängig die Annahme, es laufe nur eine Instanz (Caffeine-Caches, Chat-Memory, `@Scheduled` ohne Leader-Election, Job-Recovery). ADR mit vollständiger Fundstellenliste, Nachtragsregel und Multi-Instanz-Umbau-Skizze je Fundstelle.

**Geliefert:** ADR-0021 (Status „Vorgeschlagen") mit den acht genannten Fundstellen plus zwei zusätzlich bei der Verifikation gefundenen (`LibraryAccessService.grantsByLibrary`, `ActiveChatModelResolver.cache`). Der dokumentierte Widerspruch zwischen `LibraryIndexingScheduler`-Javadoc und `IndexingJobService.recoverJobsOrphanedByRestart` wurde aufgelöst (Javadoc-Korrektur, kein Code-Umbau). AGENTS.md referenziert das ADR unter „Wichtige Pfade".

**Verifikation:** `docs/decisions/0021-single-instance-betrieb.md` im Worktree vorhanden; AGENTS.md verweist unter „Wichtige Pfade" darauf.

**Themen:** doku, architektur, single-instance, adr
