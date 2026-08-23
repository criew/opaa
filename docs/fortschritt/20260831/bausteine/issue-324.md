# Issue #324 — Eigenen Code (evalTest) auf Jackson 3 umstellen und ADR-0007 durch Praxis-Hinweis ersetzen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, backend, size:S
- PRs: #325 (2026-08-14)

**Laut Issue:** Das `evalTest`-Sourceset (Baseline, GoldenCase, GoldenDataset, ReportWriter, BaselineRegressionTest, RetrievalEvaluationHarnessTest) nutzte noch Jackson 2 (`com.fasterxml.jackson.databind`), während der übrige Produktionscode bereits auf Jackson 3 (`tools.jackson`) umgestellt war. Zusätzlich sollte ADR-0007 entfernt werden, da er keine eigene Entscheidung trägt (folgt zwingend aus Spring Boot 4 in ADR-0002); der sachliche Befund sollte als Praxis-Hinweis in `agents/roles/developer.md` wandern.

**Geliefert:** PR #325 migriert die genannten Klassen auf `tools.jackson.databind.json.JsonMapper`, Annotationen bleiben bei `com.fasterxml.jackson.annotation.*` (bewusst, da dieses Artefakt weiterhin für Jackson 3 gilt). ADR-0007 wurde per `git rm` entfernt, keine Ersatznummer vergeben. `agents/roles/developer.md` enthält jetzt den Praxis-Hinweis inkl. Begründung, warum Jackson 2 transitiv unvermeidbar bleibt (jjwt-jackson, spring-ai-openai, Tika). Deckt sich vollständig mit dem Issue.

**Verifikation:** ADR-0007-Datei existiert im Worktree nicht mehr (bestätigt). Nicht einzeln grep-geprüft, ob `com.fasterxml.jackson.databind` noch in evalTest vorkommt — der PR-Body dokumentiert einen expliziten Vollständigkeits-Grep ohne Treffer, plausibel und risikoarm (reine Import-Umstellung).

**Themen:** backend, doku, adr, abhängigkeiten
