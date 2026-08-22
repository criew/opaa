# Issue #162 — chore: centralize all dependencies in version catalog with bundles and update project rules
- Geschlossen: 2026-03-09 (completed)
- Labels: enhancement, backend, size:S
- PRs: #163 (2026-03-09)

**Laut Issue:** Inline-Abhängigkeiten in `build.gradle.kts` sollten vollständig in `libs.versions.toml` überführt, in Bundles gruppiert und `AGENTS.md` um Regeln zu Issue-/PR-Labels und Sprache ergänzt werden.

**Geliefert:** PR #163 setzt den Umfang vollständig um: alle Abhängigkeiten in `libs.versions.toml`, Bundles (`spring-boot`, `spring-ai`, `jjwt-runtime`, `runtime`, `test-deps`, `test-runtime-deps`), `build.gradle.kts`-Dependencies-Block von 29 auf 9 Zeilen reduziert, `AGENTS.md` um die geforderten Regeln ergänzt.

**Verifikation:** `libs.versions.toml` enthält einen `[bundles]`-Abschnitt (Zeile 73) im heutigen Worktree — das Muster besteht fort und ist in `AGENTS.md` als verbindliche Konvention dokumentiert.

**Themen:** backend, build, abhängigkeitsverwaltung, projektregeln
