# Issue #114 — feat(workspace): membership management and roles API
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: #140 (2026-03-07)

**Laut Issue:** REST-API zur Mitgliederverwaltung — Hinzufügen/Entfernen/Rollenänderung durch Admin/Owner, Eigentümer nicht entfernbar, Ownership-Transfer nur durch Owner, Rollenhierarchie `VIEWER < EDITOR < ADMIN < OWNER` (Admins verwalten nur Viewer/Editor, nur Owner befördert zu Owner), Mitgliederliste für alle Mitglieder, Integrationstests.

**Geliefert:** PR #140 (gemeinsam mit #113) implementiert Auflisten/Hinzufügen/Entfernen/Rollenänderung/Ownership-Transfer, setzt die Rollenhierarchie durch, verweigert Mitgliederaufnahme in persönliche Workspaces und blockiert die Entfernung des Owners — deckungsgleich mit der Anforderung.

**Verifikation:** Wie bei den Geschwister-Issues #111–#113 — das `workspace`-Paket existiert im heutigen Code nicht mehr, ersetzt durch das umfassendere Space-/Asset-Rollenmodell (`AssetRole`, Gruppen aus Verzeichnisabgleich laut Issue #63). Die hier gelieferte Rollenhierarchie-Logik ist konzeptioneller Vorläufer des heutigen Modells.

**Themen:** workspace, spaces, backend, rbac, mitgliederverwaltung
