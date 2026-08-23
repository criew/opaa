# Issue #228 — ci(eval): Retrieval-Regressionsjob mit Baseline und Schwellenwerten
- Geschlossen: 2026-08-03 (completed)
- Labels: enhancement, size:M, ci, evaluation
- PRs: #301 (2026-08-03)

**Laut Issue:** Der Retrieval-Harness aus #227 soll automatisiert in GitHub Actions gegen eine committete Baseline laufen und bei Qualitätsverlust fehlschlagen. Auslöser: nächtlicher Zeitplan auf `main`, `workflow_dispatch`, Label `evaluation` an einem PR — bewusst nicht bei jedem PR. Zweistufiges Fehlerkriterium (harte Untergrenze + max. Verschlechterung, Vorschlag 0,03 absolut), Delta-Tabelle als PR-Kommentar, Laufzeit unter 20 Minuten, keine Secrets nötig, Baseline-Update-Verfahren dokumentiert.

**Geliefert:** `io.opaa.eval.BaselineComparator` vergleicht Fixpunkte (Manifest, Golden-Dataset-Hash, Modell-Digest, Chunk-Größe, Messvertrag-Version) exakt und meldet bei Abweichung „Baseline ungültig" statt einer Regressionsaussage. Toleranz je Gruppe/Metrik über eine Formel `min(max(0.12·Baselinewert, 1/n, 0.02), 0.05)` statt des im Issue vorgeschlagenen festen 0,03-Werts — begründet mit sehr unterschiedlicher Streuung zwischen großen/kleinen Gruppen. Zusätzlich baseline-unabhängige harte Untergrenzen für die vier Gesamtmetriken. Workflow `.github/workflows/retrieval-regression.yml` mit Modell-Cache über `actions/cache`; Fehlschlag beim nächtlichen/manuellen Lauf legt automatisch ein GitHub-Issue an. Baseline (`eval/baseline/comic-characters.json`) mit gepinntem `nomic-embed-text:v1.5`.

**Verifikation:** `.github/workflows/retrieval-regression.yml`, `eval/baseline/comic-characters.json`, `eval/baseline/README.md`, `backend/src/evalTest/java/io/opaa/eval/BaselineComparator.java` und `BaselineRegressionTest.java` existieren im heutigen Code. ADR-0013 zum Fehlerkriterium liegt unter `docs/decisions/`.

**Themen:** eval, retrieval, ci, github-actions, backend, doku
