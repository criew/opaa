# Issue #4 — MVP: Define and implement minimal viable product
- Geschlossen: 2026-02-18 (completed)
- Labels: epic, mvp, size:L
- PRs: #5 (2026-02-18)

**Laut Issue:** Auf Basis der Produktvision (docs/VISION.md) den MVP-Scope definieren: Kernwertversprechen, technisches Fundament, initiale Integrationen (Datenquelle, LLM, Frontend) und Erfolgskriterien festlegen. Erwartet werden ein MVP-Scope-Dokument, eine Technologie-ADR und eine erste Aufgabenzerlegung.

**Geliefert:** PR #5 definiert den MVP als Q&A-System über indexierte Dokumente mit Quellenangaben, dokumentiert in `docs/MVP.md`, und legt die Technologieentscheidung in ADR-0002 fest (Java/Spring Boot + Spring AI, React/TypeScript/MUI, PostgreSQL + pgvector, Apache Tika, OpenAI-kompatible API). Auth, Multi-Tenancy, Chat-Integrationen und Kubernetes wurden explizit als out-of-scope markiert. Deckt die Anforderung vollständig ab.

**Verifikation:** `docs/decisions/0002-mvp-technology-stack.md` existiert weiterhin im Worktree. `docs/MVP.md` existiert dagegen nicht mehr — laut `git log --follow` wurde die Datei zuletzt im Commit „docs: Einstieg und Umsetzungsstand auf die neue Ausrichtung angleichen" (14.08.2026) angefasst; README und Einstiegsdokumentation wurden dabei auf eine neue Ausrichtung umgestellt (weg von Fortune-500/SaaS-Zielgruppen, austauschbare Vektor-DB-Versprechen entfernt, da der Stack durch ADR-0002 längst festgelegt ist). Der MVP-Scope wurde damit nicht verworfen, sondern in aktuellere Einstiegsdokumentation überführt.

**Themen:** doku, mvp, projektsetup, adr
