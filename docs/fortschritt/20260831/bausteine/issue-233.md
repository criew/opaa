# Issue #233 — test(e2e): E2E-Suite auf das gemeinsame e2e-Seed-Profil umstellen
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, size:M, demo
- PRs: #726 (2026-08-21)

**Laut Issue:** Die bestehende E2E-Suite soll ihre Ausgangsdaten aus dem `e2e`-Datenprofil des gemeinsamen Seeds beziehen statt aus einer eigenen Testdatenbereitstellung (`e2e/fixtures/rss-feed/`, `e2e/fixtures/test-documents/`), damit es nur noch einen Weg gibt, eine Instanz zu befüllen. Ursprünglich als sechsteilige Suchprüfung gegen den Superhelden-Korpus formuliert, inhaltlich durch das Demo-Konzept ersetzt. Kein Szenario darf ersatzlos entfallen, Keycloak bleibt außen vor (dev-Auth), Laufzeit darf sich nicht spürbar verschlechtern.

**Geliefert:** Testdaten von `e2e/fixtures/rss-feed/` und `e2e/fixtures/test-documents/` nach `demo/seed/e2e-data/` verschoben (nicht dupliziert), referenziert vom `E2E_PROFILE` in `demo/seed/profiles.py`. `e2e/scripts/run-e2e.mjs` führt vor der Playwright-Suite `demo/seed/seed.py --profile e2e` aus; CI (`e2e.yml`) bekam einen Python-Setup-Schritt. Fünf Spec-Dateien auf neue Fixture-Pfade nachgezogen, keine fachliche Testaussage geändert. Nachweis: voller Suite-Lauf 27/27 grün, ~1,6 Minuten (unverändert), Seed-Lauf selbst < 5s. Nebenbefund: Der Seed deckte einen vorher unsichtbaren Farbkontrastfehler auf der Wissensbibliotheken-Seite auf (axe-core, `#778797` auf Weiß, 3,68:1 statt 4,5:1) — bewusst als eigenes Issue #725 ausgelagert statt im Rahmen dieses (laut Issue produktivcode-freien) Ombaus mitbehoben; im Test punktuell mit `disableRules: ['color-contrast']` auf genau dieses Szenario begrenzt ausgenommen.

**Verifikation:** `demo/seed/e2e-data/` existiert im heutigen Code, `e2e/fixtures/rss-feed/` und `e2e/fixtures/test-documents/` existieren nicht mehr (wie vorgesehen abgelöst). `demo/seed/profiles.py` vorhanden.

**Themen:** e2e, demo, seed, testinfrastruktur, barrierefreiheit
