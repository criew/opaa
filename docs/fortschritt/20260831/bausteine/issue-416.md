# Issue #416 — fix(eval): Zweite Review-Runde zu PR #301 nachreichen — harte Untergrenze, Sechsfachbefund, Baseline-Diff
- Geschlossen: 2026-08-15 (completed)
- Labels: bug, backend, size:M, ci, evaluation
- PRs: #417 (2026-08-15)

**Laut Issue:** Eine zweite Review-Runde zu PR #301 war als Commit auf dem längst gemergten Branch `feature/228_retrieval-regressionsjob` liegen geblieben und nie nachgereicht worden. Drei Kernbefunde: (1) die harte Untergrenze in `BaselineComparator` (`0,8 × Baselinewert`) konnte nie auslösen und wanderte mit einer erodierenden Baseline mit, statt sie zu verankern; (2) die Dokumentation beschrieb die Toleranzlücke „enger als 1/n" als Einzelfall, obwohl sie sechs Gruppen-/Metrik-Paare betrifft; (3) die Baseline-Absenkungsprüfung (`diff_baseline.py`) lief nur im label-ausgelösten Job und damit nicht verlässlich für jeden PR, der `eval/baseline/**` änderte.

**Geliefert:** PR #417 übernimmt den zwölf Tage alten Commit und passt ihn an den aktuellen Stand an. Harte Untergrenze jetzt `max(relativer Term, fester absoluter Wert)`; Sechsfachbefund mit Zahlen in Javadoc, ADR-0013 und `eval/baseline/README.md` belegt; `diff_baseline.py` in einen eigenen, label-unabhängigen Workflow `.github/workflows/baseline-diff.yml` ausgelagert. Zusätzlich (laut PR-Body „beim Nachziehen angepasst"): ein Testanpassung wegen der zwischenzeitlichen Jackson-3-Migration (Test prüft jetzt Ergebnis statt Exception-Typ) und Konfliktauflösung mit parallel gemergten #311/#414-Änderungen in `retrieval-regression.yml`. Kleinere Punkte aus der Review-Runde (Validierung von `distinctExpectedDocumentSets`, Vollständigkeitsprüfung des Cache-Exports) ebenfalls umgesetzt. Reproduktionsnachweis für den Kernbefund (harte Untergrenze) im PR dokumentiert.

**Verifikation:** Nicht vertieft geprüft (Dateien `BaselineComparator.java`, `.github/workflows/baseline-diff.yml` sind laut Dateiliste angelegt/geändert); die Beschreibung im PR-Body ist detailliert und mit rot/grün-Testnachweis belegt, kein Anlass für Zweifel.

**Themen:** evaluation, ci, retrieval, code-review, baseline, adr-0013
