# Issue #654 — feat(frontend): Dunkles Farbschema an das dunkle Schema der Claude-Docs anlehnen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #656 (2026-08-20)

**Laut Issue:** Maintainer-Entscheidung, das dunkle Farbschema von Navy-auf-Navy (`#012142`) auf eine neutrale, an code.claude.com angelehnte Grauskala (`#09090B`/`#171717`/`#252525` usw.) umzustellen, mit klarer Flächenstaffelung. Seitenleiste soll im hellen Schema Navy bleiben (`navyRoles`), im dunklen dem neuen Schema folgen. Guidelines und Tokens sollen synchron aktualisiert werden, Kontraste nach accessibility.md.

**Geliefert:** Genau wie gefordert umgesetzt: neue Carbon-Dunkelskala in `tokens.ts`, `navyRoles` für die helle Seitenleiste, `createSidebarTheme(appMode, branding)` kapselt die Wahl, `guidelines.md` synchron angepasst, Kontrastnachweise (u. a. fg-2 auf bg ≈ 9:1) im PR dokumentiert. Keine inhaltlichen Abweichungen vom Issue.

**Verifikation:** `frontend/src/theme/tokens.ts` und `frontend/src/theme/theme.ts` existieren im Worktree.

**Themen:** frontend, design, dark-mode, theming, accessibility
