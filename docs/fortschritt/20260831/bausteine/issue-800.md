# Issue #800 — fix(frontend): Review-Nachbesserungen am globalen Rahmen — mobile Spalte, Rollenbindung, Profilblock, Testlücken
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, frontend, size:M
- PRs: #803 (2026-08-23)

**Laut Issue:** Sammel-Nachbesserung für Review-Befunde zu #794 und #795, die erst nach dem (Maintainer-angeordneten) Merge eintrafen: mobiler Überlauf der Admin-Sekundärspalte bei 320 px, rollenunabhängige Sichtbarkeit der Admin-`sections` für Nicht-Admins, Mockup-Abweichungen bei Flächenfarben, fehlende Guidelines-Ergänzung, unzureichende Typhärtung von `GlobalAreaLayout`, fehlender mobiler/E2E-Testnachweis; dazu Profilblock-Doppelung der E-Mail, ein Absturz bei leerem `displayName`, ein nie ausgeführter Test sowie veraltete Sidebar-Testrouten.

**Geliefert:** Laut PR-Beschreibung wurden alle genannten Befunde aus #794 und #795 abgearbeitet — plus zusätzlich Befunde aus dem (zeitlich dazwischen gemergten) #799-Review, die im Issue-Text nicht genannt sind, aber ausdrücklich als thematisch zugehörig benannt werden. Mobile Spalte umgebrochen statt gescrollt, `sections` an `SYSTEM_ADMIN` gebunden, `border-strong`/`bg1` gemäß Mockup, Guidelines 2.3 ergänzt, `GlobalAreaLayout` typisiert gehärtet, gemeinsames `userInitial()` mit Trim, `clearThemeMode`-Test tatsächlich ausgeführt, Sidebar-Tests auf `/spaces` umgestellt. Reproduktionsnachweis für die zwei kritischen Bugs (E-Mail-Doppelung, Initialen-Absturz) laut PR erbracht.

**Verifikation:** `frontend/src/utils/userInitial.ts` und `userInitial.test.ts` existieren im Worktree; `frontend/src/layouts/GlobalAreaLayout.tsx`, `GlobalRail.tsx`, `Sidebar.tsx` ebenfalls vorhanden.

**Themen:** frontend, barrierefreiheit, navigation, code-review, mobile, design-system
