# Issue #814 — fix(frontend): isGlobalAreaPath normalisiert Trailing Slashes nicht — /spaces/ zeigt die Space-Spalte
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, frontend, size:S
- PRs: #816 (2026-08-24)

**Laut Issue:** Review-Befund zu PR #811: React Router matcht Routen slash-tolerant (`/spaces/` rendert die Übersicht), `isGlobalAreaPath` verglich aber exakt — auf `/spaces/` und `/spaces/new/` erschien die Navy-Spalte zusätzlich zur Kartenübersicht bzw. zum Assistenten, genau der von #809 entfernte Zustand. Verlangt: Pfad-Normalisierung (Trailing Slashes entfernen), Reproduktionsnachweis, Korrektur der Doku-Invariante in `GlobalAreaLayout.tsx`/`globalArea.ts`, Ergänzung des AppShell-Test-Harness um `/spaces/new` und ein nachgezogener Sidebar-Kommentar.

**Geliefert:** Pfad-Normalisierung vor dem Vergleich implementiert; Testfälle für `/spaces/`, `/spaces/new/`, `/libraries/`. Doc-Invariante und Sidebar-Kommentar korrigiert. Reproduktionsnachweis laut PR: Test schlägt vor dem Fix fehl (`2 failed | 18 passed`), danach 652/652 grün. Ein bestehendes, unabhängiges Flake-Szenario (`space-chats`, ordnungs-/korpusabhängig) wurde separat als Issue #815 ausgelagert statt hier mitbehoben — nachvollziehbare Abgrenzung, keine Lieferlücke gegenüber diesem Issue.

**Verifikation:** `frontend/src/layouts/globalArea.ts` enthält im Worktree einen Kommentar zur Trailing-Slash-Toleranz von React Router; die Normalisierungslogik ist an der Definition von `GLOBAL_AREA_EXACT_PATHS` erkennbar vorhanden (siehe auch #809-Verifikation).

**Themen:** frontend, spaces, navigation, testing
