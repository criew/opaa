# Issue #585 — feat(frontend): A11y-Basisausstattung — Landmarken, Fokusführung, reduzierte Bewegung
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #629 (2026-08-20)

**Laut Issue:** Landmarken-Struktur (`header`/`nav`/`main`/`footer`), Sprungmarke, Fokus-Management bei Routenwechsel auf die Seitenüberschrift, sichtbarer Fokus-Stil aus dem Designsystem, `prefers-reduced-motion`, Dokumenttitel je Seite, Live-Region für asynchrone Statusmeldungen.

**Geliefert:** Alle genannten Punkte umgesetzt (`SkipLink`, `PageHeading`, `usePageTitle`, Fokusring-Fix für MUI `ButtonBase` als während der Umsetzung entdeckter Nebenbefund, `role="status"`-Live-Regionen). Eine Abnahmekriterium blieb im PR selbst ausdrücklich offen: „Routenwechsel wird vom Screenreader angesagt" — laut PR-Text war dafür eine **VoiceOver-Stichprobe durch den Maintainer offen**, technisch nur indirekt über Fokus/Titel belegt. Ob diese manuelle Prüfung nachträglich erfolgte, ist aus den Daten nicht ersichtlich.

**Verifikation:** `frontend/src/components/a11y/SkipLink.tsx` und die übrigen im PR gelisteten Dateien (`PageHeading.tsx`, `AppShell.tsx` etc.) existieren im aktuellen Code.

**Themen:** frontend, barrierefreiheit, fokusfuehrung, ui
