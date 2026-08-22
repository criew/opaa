# Issue #644 — Buildzeiten und Merge-Durchsatz optimieren (Build-Cache, Merge Queue, CI-Zuschnitt)
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, ci
- PRs: #647 (2026-08-20)

**Laut Issue:** Vier Problembereiche im Entwicklungsprozess: lange Buildzeiten (kein Build-Cache), CI-Stau vor dem Merge (strict-Branch-Protection serialisiert Merges), ausgelastete Rechner durch mehrfache identische Gradle-Arbeit in parallelen Worktrees, sehr große Worktrees (~15 GB). Geplante Maßnahmen: (1) Gradle-Build-Cache/Parallelisierung/Configuration-Cache, (2) GitHub Merge Queue mit `strict` deaktiviert, (3) CI-Zuschnitt (Concurrency, Pfadfilter, keine doppelte Testausführung — eigener `openAiIntegrationTest`-Task), (4) Worktree-Hygiene-Regel in AGENTS.md, (5) pnpm-Migration bewusst zurückgestellt.

**Geliefert:** Maßnahmen 1–4 umgesetzt. Abweichung von Maßnahme 2: Statt der geplanten GitHub Merge Queue wurde auf Wunsch des Maintainers nur `strict` deaktiviert (sofortiges Mergen konfliktfreier PRs); der `merge_group`-Trigger blieb als Vorbereitung im Workflow erhalten, die Merge Queue selbst wurde nicht aktiviert. Build-Cache (benutzerweit, `~/.gradle/caches/build-cache-1`) verifiziert: voller Build 10 min, Folgebuild mit Cache 33 s. `openAiIntegrationTest` als eigener Task, aus `test`/`build` ausgeschlossen. `ci.yml`/`e2e.yml` mit `concurrency`-Block und Pfadfiltern (`dorny/paths-filter`). AGENTS.md um Auto-Merge-Arbeitsweise, Worktree-Aufräumregel mit Begründung und `npm ci`-Hinweis ergänzt. Maßnahme 5 (pnpm) laut Issue bewusst zurückgestellt, kein Bestandteil dieses PRs.

**Verifikation:** `backend/gradle.properties` enthält `org.gradle.caching=true`, `org.gradle.parallel=true`, `org.gradle.configuration-cache=true` mit Verweis auf #644. `ci.yml` verwendet aktuell `actions/checkout@v7` (durch #625 später weiter angehoben) und enthält laut Grep-Treffer weiterhin Cache-Schritte — Grundstruktur besteht fort.

**Themen:** ci, projektsetup, agenten-organisation, build-performance, worktrees
