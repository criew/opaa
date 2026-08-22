# Issue #340 — docs: Feature-Spezifikationen entlang der elf Themenbereiche neu schneiden
- Geschlossen: 2026-08-15 (completed)
- Labels: documentation, size:L
- PRs: keine direkt verlinkt — Arbeit lief über vier Sub-Issues mit eigenen PRs: #360→#368 (2026-08-14, A/B/E), #361→#365 (2026-08-14, C/D), #362→#371 (2026-08-14, F/G/H), #363→#366 (2026-08-14, I/J/K)

**Laut Issue:** Jeder der elf Themenbereiche soll genau eine zuständige Feature-Spezifikation bekommen — teils Überarbeitung bestehender Dateien (`data-indexing-rag.md`, `spaces-and-assets.md`, `access-control.md`, `llm-integration.md`, `user-frontends.md`, `deployment-infrastructure.md`), teils Neuanlage (`knowledge-sources.md`, `agents-and-tools.md`, `security-and-compliance.md`, `monitoring-and-governance.md`, `public-sector.md`).

**Geliefert:** Laut Abschlusskommentar auf dem Issue wurde die Arbeit in vier Bündel-Sub-Issues aufgeteilt (#360 A/B/E, #361 C/D, #362 F/G/H, #363 I/J/K), die jeweils über eigene PRs (#368, #365, #371, #366) gemergt wurden. Das Issue selbst hat daher keinen direkt verlinkten PR — im gelieferten Datensatz taucht `linkedPRs: []`, obwohl es inhaltlich vollständig erledigt ist. Laut Abschlusskommentar wurde bei der Nachprüfung in zwei Bündeln Inhalt ersatzlos gestrichen statt verschoben; das wurde nachgetragen und in #366/#368 dokumentiert.

**Verifikation:** Alle fünf neuen Spezifikationsdateien existieren im Worktree (`docs/features/knowledge-sources.md`, `agents-and-tools.md`, `security-and-compliance.md`, `monitoring-and-governance.md`, `public-sector.md`). Damit ist der Abschlusskommentar durch den heutigen Dateibestand bestätigt.

**Themen:** doku, produktvision, feature-spezifikation, agenten-organisation
