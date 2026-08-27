# Issue #751 — Renovate für automatisierte Abhängigkeits-Updates konfigurieren (lokale Ausführung, kein Cloud-Service)
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, size:M, ci
- PRs: #911 (2026-08-25)

**Laut Issue:** Abhängigkeits-Updates (Gradle-Versionskatalog, npm/pnpm, GitHub Actions, Docker) wurden manuell und unregelmäßig eingepflegt. Gefordert war Renovate — ausdrücklich **selbst betrieben, ohne den Mend-Cloud-Service** (keine externe App im Repository) — mit Managern für alle relevanten Abhängigkeitsquellen, sinnvoller Gruppierung, deutschen PR-Titeln nach Conventional Commits, dokumentierter Betriebsanleitung und einem Probelauf.

**Geliefert:** Wie gefordert. `renovate.json5` auf Basis von `config:recommended`, plus projektspezifische Regeln: deutsche Commit-/PR-Texte, Labels je Manager, max. 5 gleichzeitig offene PRs, Spring-Plattform als ein gebündelter PR, kein Digest-Pinning für Docker-Images (dokumentierte Projektentscheidung aus PR #453). `docs/renovate.md` beschreibt Dry-Run und echten Lauf. Probelauf-Nachweis im PR: Config validiert erfolgreich, 8 Manager/26 Manifeste/191 Abhängigkeiten erkannt, 36 geplante Update-Branches, Versionsänderungen landen konstruktionsbedingt im Versionskatalog statt in `build.gradle.kts`. Der erste PR-erzeugende Lauf war laut PR erst nach dem Merge möglich (Renovate liest die Konfiguration aus dem Default-Branch).

**Verifikation:** `.github/workflows/renovate.yml`, `renovate.json5`, `docs/renovate.md` existieren im Worktree — konsistent mit der beschriebenen Lieferung.

**Themen:** ci, projektsetup, abhängigkeitsverwaltung, renovate
