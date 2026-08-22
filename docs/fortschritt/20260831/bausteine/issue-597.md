# Issue #597 — feat(frontend): Übrige Seiten und Dialoge an das neue Design angleichen
- Geschlossen: 2026-08-21 (completed)
- Labels: frontend, size:M
- PRs: #701 (2026-08-21)

**Laut Issue:** Als letztes Migrations-Issue des Redesign-Epics sollten `LibraryDetailPage`, `SpaceManagementPage`, `GroupManagementPage`, `SettingsPage` sowie die verbleibenden Dialoge (`LibraryGrantsDialog`, `CreateGroupDialog`, `EditLibrarySourceDialog`) und der `ErrorBoundary`-Fehlerzustand auf Token-Theme und Guidelines umgestellt werden, unter Wiederverwendung bereits etablierter Muster.

**Geliefert:** PR #701 stellt alle genannten Seiten und Dialoge um: einheitliche Kopfzeilen mit Zählzeile, Eyebrow-Abschnittsköpfe, Formulare im 40-px-Feldmuster, Mono-Badges. Zwei neue geteilte Bausteine (`MetaBadge`, `SectionHead`) statt Kopien. Einstellungsseite erhält zusätzlich einen Abschnitt „Erscheinungsbild des Hauses“ mit Verweis auf Branding. Kleine Terminologieänderung: die Verteilungsstufe wird auf der Bibliothek-Detailseite konsistent zum Anlage-Assistenten benannt.

**Verifikation:** `frontend/src/components/MetaBadge.tsx` und `frontend/src/components/SectionHead.tsx` existieren im heutigen Worktree; die im PR genannten Seiten (`SettingsPage.tsx`, `GroupManagementPage.tsx`, `SpaceManagementPage.tsx`, `LibraryDetailPage.tsx`) sind vorhanden.

**Themen:** frontend, redesign, ui, barrierefreiheit
