# Issue #941 — CI: Baseline-Absenkungs-Wächter prüft nur comic-characters — city-landmarks bekommt falschen Freispruch
- Geschlossen: 2026-08-27 (completed)
- Labels: bug, size:S, ci, evaluation
- PRs: #944 (2026-08-27)

**Laut Issue:** Review-Fund aus PR #940: `.github/workflows/baseline-diff.yml` und `eval/baseline/diff_baseline.py` waren fest auf `comic-characters.json` verdrahtet. Senkt ein PR nur `city-landmarks.json` ab (wie #940 legitim tat), vergleicht der Wächter nichts und postet trotzdem einen Freispruch — ein falscher Freispruch genau in dem Fall, für den der Wächter existiert. Gefordert: über alle `eval/baseline/*.json`-Dateien iterieren, mit Reproduktionsnachweis (künstlich abgesenkter city-landmarks-Wert muss im Kommentar auftauchen).

**Geliefert:** Beide Dateien iterieren jetzt generisch über jede `eval/baseline/*.json`-Datei; der PR-Kommentar nennt das Ergebnis pro Datei einzeln. Reproduktionsnachweis wie gefordert erbracht (lokal simuliert, da der Workflow nur auf PR-Events läuft): rot mit der alten, fest verdrahteten Version, grün mit der neuen.

**Verifikation:** Im lokal ausgecheckten Worktree-Stand (Commit `5c016998`) ist die Datei `.github/workflows/baseline-diff.yml` noch auf dem **alten, fest verdrahteten** Stand (`comic-characters.json` hartcodiert) — der Worktree liegt einen Commit hinter `origin/main`. Per `git fetch` bestätigt: Der Merge-Commit `1c38b80d` (PR #944) ist auf `origin/main` vorhanden und ist dessen aktuelle Spitze. Der Fix ist also geliefert, nur in diesem lokalen Worktree-Checkout noch nicht sichtbar — kein inhaltlicher Befund, sondern ein veralteter lokaler Checkout.

**Themen:** ci, evaluation, qualitätssicherung, baseline
