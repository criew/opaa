# Issue #107 — feat: Introduce Workspaces & Access Control
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, epic, workspace
- PRs: keine (Epic ohne eigenen PR)

**Laut Issue:** Großes Epic zur Einführung eines Workspace-basierten Zugriffsmodells mit Authentifizierung, rollenbasierten Berechtigungen und Workspace-gefilterter Suche, in 6 Phasen (Auth/Nutzerverwaltung, Workspace-Kern, Workspace-bewusste Datenpipeline, Dokumentverwaltung, Frontend, Integration/Qualität) mit den Unter-Issues #108–#125.

**Geliefert:** Als Epic selbst nichts direkt — umgesetzt über die referenzierten Sub-Issues, von denen dieser Chunk #108–#114 (Phase 1 und 2) abdeckt (siehe dortige Bausteine). Bemerkenswert: Das Konzept „Workspace" wurde nach diesen frühen Phasen in „Space" umbenannt und strukturell erweitert (Bibliotheken, Asset-Rollen, Gruppen aus Verzeichnisabgleich) — das ursprüngliche Epic-Design war also ein Zwischenschritt, kein Endzustand.

**Verifikation:** Das Java-Paket `io.opaa.workspace` existiert im heutigen Worktree nicht mehr; an seiner Stelle steht `io.opaa.space`. Migration `008-rename-workspace-to-space.yaml` dokumentiert die Umbenennung im Datenbankschema explizit.

**Themen:** epic, workspace, spaces, auth, access-control
