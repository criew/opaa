# Issue #583 — feat(frontend): Branding über die Weboberfläche konfigurieren und im Theme anwenden
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #643 (2026-08-20)

**Laut Issue:** Frontend soll Branding-Konfiguration beim Start laden, in `createAppTheme` einspeisen, Logo/Produktname in Seitenleiste und Anmeldeseite sowie Dokumenttitel übernehmen, ein Verwaltungsformular für `SYSTEM_ADMIN` mit Live-Vorschau und WCAG-Kontrastwarnung bieten.

**Geliefert:** Wie gefordert umgesetzt (`brandingStore`, `BrandMark`, Formular unter `/admin/branding`, Live-Vorschau in beiden Farbschemata, Kontrastprüfung als Warnung ohne Blockade). Zusätzlich musste der PR den in #582 fehlenden „permitAll"-Commit nachziehen, da `GET /api/v1/branding` sonst für Unangemeldete 401 geliefert hätte und die Anmeldeseite ohne Branding dargestellt worden wäre. Nebenbefund dokumentiert: Die OPAA-Standard-Akzentfarbe `#1292EE` erreicht nur 3,3:1 Kontrast gegen Weiß (gefordert 4,5:1) — im Widerspruch zur Behauptung in `docs/design/guidelines.md#24-kontrast`. Der Autor hat dafür bewusst kein eigenes Issue angelegt, sondern es hier vermerkt; laut PR-Text sollte dies zu #584/#598 gehören.

**Verifikation:** `frontend/src/stores/brandingStore.ts` vorhanden. `frontend/src/theme/tokens.ts` führt `accent: blue[500]` = `#1292EE` weiterhin als Standardwert — der dokumentierte Kontrast-Nebenbefund ist im Code nicht behoben, offenbar folgt er separaten Issues (#634 laut #586).

**Themen:** frontend, branding, theme, barrierefreiheit, kontrast
