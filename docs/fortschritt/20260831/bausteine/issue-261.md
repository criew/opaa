# Issue #261 — fix(ci): Tagesreport landet beim ersten Lauf auf falschem Branch
- Geschlossen: 2026-08-02 (completed)
- Labels: bug, size:S, ci
- PRs: #262 (2026-08-02)

**Laut Issue:** Der erste Lauf des Report-Workflows (`.github/workflows/daily-report.yml`) erzeugte den Tagesreport korrekt, konnte ihn aber nicht veröffentlichen (`error: src refspec gh-pages does not match any`). Ursache: Der `continue-on-error`-Checkout-Schritt legte beim ersten Lauf (ohne existierenden `gh-pages`-Branch) bereits ein Git-Repository an, wodurch die nachfolgende `if [ ! -d .git ]`-Prüfung die Initialisierung mit `git init -b gh-pages` überspringt und HEAD auf dem Standardbranch (`master`) verbleibt.

**Geliefert:** Der Zielbranch wird jetzt unabhängig davon gesetzt, ob das Repository vom Checkout oder vom Skript angelegt wurde — via `git symbolic-ref`, das laut PR-Beschreibung auch ohne ersten Commit funktioniert. Die PR-Beschreibung dokumentiert die Prüfung von vier Zuständen (frisches Repo ohne Commit, Folgelauf mit Historie, Lauf ohne Änderungen, HEAD fälschlich auf `master`) statt eines automatisierten Tests, da es sich um reine Workflow-Logik handelt. Deckt sich mit den drei Abnahmekriterien des Issues.

**Verifikation:** `.github/workflows/daily-report.yml` enthält aktuell den Kommentar „Existiert gh-pages noch nicht, scheitert der vorangehende Checkout am …" sowie die Zeile `if [ "$(git symbolic-ref --short HEAD 2>/dev/null)" != "gh-pages" ]; then git symbolic-ref HEAD refs/heads/gh-pages; fi` — der beschriebene Fix ist im aktuellen Workflow vorhanden.

**Themen:** ci, agenten-organisation
