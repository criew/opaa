# Issue #153 — refactor: Remove Spring "mock" profile from codebase
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement, backend, size:M
- PRs: #155 (2026-03-08), #160 (2026-03-08)

**Laut Issue:** Der Spring-`mock`-Profil (Backend ohne LLM/DB-Anbindung) sollte vollständig entfernt werden — Mock-Controller, `@Profile("!mock")`-Annotationen, `application.yml`-Abschnitt, zugehörige Tests und Dokumentationsverweise.

**Geliefert:** PR #155 entfernt Mock-Controller (`MockQueryController`, `MockIndexingController`) samt Tests, `@Profile`-Annotationen auf 5 Produktivklassen und aktualisiert die Dokumentation. PR #160 räumt eine übersehene `MockSecurityConfig.java` nach (letzte verbliebene `@Profile("mock")`-Klasse) — vollständiger Abschluss des im Issue geforderten Umfangs.

**Verifikation:** `grep` nach `Profile("mock")`/`Profile("!mock")` im heutigen Backend-Code liefert keine Treffer — das Spring-Profil ist vollständig entfernt geblieben.

**Themen:** backend, refactoring, cleanup, architektur
