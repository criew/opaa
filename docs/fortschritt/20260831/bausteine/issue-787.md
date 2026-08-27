# Issue #787 — feat(frontend): Globaler Verwaltungsrahmen — helle Fläche mit „Global“-Badge für die Administration
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #794 (2026-08-23)

**Laut Issue:** Für die Administrationsseiten (`/admin/branding`, `/admin/groups`, `/admin/models`) sollte gemäß Mockup 2b ein eigener „globaler Verwaltungsrahmen“ entstehen: helle Fläche statt der Navy-Space-Spalte, eine Sekundärspalte „Administration“ mit „GLOBAL“-Badge und Geltungsbereichs-Hinweis. Der Rahmen sollte als wiederverwendbare Komponente geschnitten sein, damit Folge-Issues (Benutzer-Einstellungen, Bibliothekskatalog) ihn nachnutzen können. Bereiche ohne Backend-Funktion (OIDC, E-Mail-Server, Scheduler, Audit) sollten ausdrücklich nicht als Platzhalter erscheinen.

**Geliefert:** `GlobalAreaLayout` als Layout-Route mit `nav`-Landmark „Administration“, `GlobalBadge`- und `GlobalScopeNote`-Komponenten, Einbettung der drei bestehenden Admin-Seiten, `AppShell` blendet die Space-Spalte auf `/admin/*` aus. Die bisherige `AdminSectionNav`-Erreichbarkeitsbrücke (aus dem #791-Review) wurde im selben PR entfernt, weil die neue Sekundärspalte sie ablöst. Keine Abweichung vom Issue-Umfang erkennbar; die im Mockup illustrativen Bereiche ohne Backend blieben wie gefordert außen vor.

**Verifikation:** `GlobalAreaLayout.tsx`, `GlobalBadge.tsx`, `GlobalScopeNote.tsx` existieren im heutigen Worktree unter `frontend/src/components/` bzw. `frontend/src/layouts/`. `AdminSectionNav.tsx` existiert nicht mehr — laut Git-Historie (`git log --oneline -- frontend/src/components/admin/AdminSectionNav.tsx`) im selben Commit 8cc0af8e (#794) sowohl zuletzt geändert als auch entfernt, deckungsgleich mit der PR-Beschreibung.

**Themen:** frontend, navigation, admin, design-system, barrierefreiheit
