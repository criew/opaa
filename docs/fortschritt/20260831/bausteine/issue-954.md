# Issue #954 — fix(ci): Renovate schlägt npm-Releases vor, die pnpms minimumReleaseAge noch ablehnt

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, ci
- PRs: #955 (2026-08-28)

**Laut Issue:** pnpm 11 lehnt per Supply-Chain-Standard Pakete ab, die jünger als 24 Stunden
sind. Renovate kannte diese Frist nicht und schlug brandneue Releases vor, die pnpm dann in CI
und Docker-Build verweigerte (`ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION`).

**Geliefert:** PR #955 konfiguriert `minimumReleaseAge: '1 day'` in `renovate.json5`, sodass
Renovate erst Releases vorschlägt, die pnpms Frist bereits bestehen. Für die bereits
eröffneten, vorgezogenen Update-PRs wurde eine befristete CI-Ausnahme gesetzt (PR #1014).

**Verifikation:** `renovate.json5` führt `minimumReleaseAge: '1 day'`.

**Themen:** Renovate, pnpm, Supply-Chain, CI
