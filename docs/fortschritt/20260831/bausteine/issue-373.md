# Issue #373 — GitHub Pages: Landing-Page als Startseite, Tagesreport darunter verlinken
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:M, ci
- PRs: #376 (2026-08-14)

**Laut Issue:** GitHub Pages zeigte den Tagesreport als Startseite, die Landing-Page in `page/` war nirgends veröffentlicht; zusätzlich verlinkte `page/index.html` auf eine tote Adresse (`demo.opaa.ewerlin.com` statt `opaa.ewerlin.com`). Ziel: Landing-Page unter `/`, Tagesreport unter `/report/`, Historie bleibt erhalten, `.nojekyll` im Wurzelverzeichnis, Tests decken die geänderte Verlinkung ab.

**Geliefert:** Wie beschrieben. `gh-pages` aufgeteilt: neuer `landing-page.yml`-Workflow veröffentlicht `page/` bei jeder Änderung unter `/`, `daily-report.yml` schreibt weiter nächtlich unter `/report/`. `migrate_pages_layout.sh` verschiebt den Altbestand einmalig und idempotent, von beiden Workflows aufgerufen, damit die Reihenfolge nach dem Merge egal ist. Tote Adresse ersetzt. Report-Übersicht verlinkt zurück auf die Landing-Page. 45 Tests grün, zwei neu für die Verlinkung. Bewusst keine Weiterleitung von der alten Bookmark-Adresse gebaut — im PR als offener Punkt für den Maintainer benannt, nicht verschwiegen.

**Verifikation:** `page/README.md` und `.github/workflows/landing-page.yml` existieren im Worktree.

**Themen:** ci, github-pages, tagesreport, landing-page, doku
