# Issue #508 — ci(e2e): Playwright-Install hängt und frisst das Job-Timeout
- Geschlossen: 2026-08-19 (completed)
- Labels: bug, ci
- PRs: #509 (2026-08-19)

**Laut Issue:** Der Schritt „Install Playwright browsers" (`--with-deps`) hing wiederholt am apt-Teil und hatte kein eigenes Timeout — der Job lief ins 20-Minuten-Limit, ohne dass die Suite je startete (zuletzt dreimal bei #504/#506). Gefordert: Cache für `~/.cache/ms-playwright`, Schritt-Timeout, Trennung von Browser-Install und System-Deps.

**Geliefert:** Wie gefordert. Browser-Cache mit Schlüssel aus der Playwright-Version in `e2e/package-lock.json`, `--with-deps` nur noch bei Cache-Miss, 6-Minuten-Timeout am Schritt.

**Verifikation:** `.github/workflows/e2e.yml` existiert und enthält Cache- und Timeout-Konfiguration für den Playwright-Install-Schritt.

**Themen:** ci, e2e, betrieb
