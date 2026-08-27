# Issue #809 — feat(frontend): Spaces-Übersicht ohne Space-Spalte — Navy-Spalte erst im gewählten Space
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #811 (2026-08-23)

**Laut Issue:** Maintainer-Feedback zum Navigationskonzept: Auf der Spaces-Übersicht (`/spaces`) und im Anlage-Assistenten (`/spaces/new`) ist noch kein Space gewählt — dort soll nur die Kartenansicht ohne Navy-Space-Spalte (Dropdown + Chat-Liste) erscheinen. Die Spalte soll erst im gewählten Space (`/spaces/:spaceId*`) sichtbar werden. Verlangt wurde außerdem eine Erweiterung von `isGlobalAreaPath` um Exakt-Pfade, angepasste Sidebar-Fallback-Tests und kein „Global"-Badge auf der Übersicht.

**Geliefert:** `/spaces` und `/spaces/new` rendern im nackten globalen Rahmen (`GlobalAreaLayout` ohne Spalte); `isGlobalAreaPath` um Exakt-Pfade (`/spaces`, `/spaces/new`) erweitert, mit Testfällen für Übersicht, Assistent, Space und Chat. Sidebar-Fallback-Tests (9×) auf `/chat` umgestellt, da dies die einzige verbleibende Route ohne `:spaceId` ist, auf der die Spalte noch rendert. Kein „Global"-Badge ergänzt. Deckt sich mit dem Issue-Umfang; laut PR-Beschreibung E2E 36/36 grün.

**Verifikation:** `frontend/src/layouts/globalArea.ts` existiert im Worktree und enthält `GLOBAL_AREA_EXACT_PATHS = ['/spaces', '/spaces/new']` mit Kommentarverweis auf #809 — Umsetzung nachvollziehbar vorhanden.

**Themen:** frontend, spaces, navigation
