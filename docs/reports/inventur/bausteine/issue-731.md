# Issue #731 — fix(api): Rate-Limit-Meldung ist englisch statt deutsch
- Geschlossen: 2026-08-21 (completed)
- Labels: bug, backend
- PRs: #733 (2026-08-21)

**Laut Issue:** Bei HTTP 429 lieferte die API `"Rate limit exceeded. Please try again later."` — englisch, entgegen der Projektsprachregel (AGENTS.md) und Abnahmekriterium von #230. Gefordert: deutsche Meldung für alle Kontingente, Prüfung ob das Frontend eigene Texte hat, Reproduktionsnachweis.

**Geliefert:** Deckungsgleich — Meldung auf "Zu viele Anfragen — bitte versuchen Sie es in Kürze erneut." umgestellt. Frontend hatte keine eigenen Texte (reicht die Backend-Meldung durch), Fixture in `chatStore.test.ts` entsprechend angepasst. Reproduktionsnachweis mit rotem (4 Testfehlschläge) und grünem Lauf im PR dokumentiert.

**Verifikation:** `backend/src/main/java/io/opaa/api/RateLimitFilter.java` existiert im Worktree.

**Themen:** api, i18n, rate-limiting, backend
