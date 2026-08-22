# Issue #276 — fix(ci): Eigenes Secret für den Tagesreport statt des Anwendungsschlüssels
- Geschlossen: 2026-08-02 (completed)
- Labels: bug, size:S, ci
- PRs: #278 (2026-08-02)

**Laut Issue:** Der Report-Workflow nutzte für die Zusammenfassung dasselbe Secret `OPAA_OPENAI_API_KEY` wie der erforderliche Status-Check `backend-integration`. Wird das Secret gesetzt, um die Zusammenfassung zu aktivieren, laufen unbeabsichtigt bei jedem Push echte, kostenpflichtige OpenAI-Aufrufe, und Anbieterstörungen könnten Merges blockieren. Gefordert: eigenes `OPAA_REPORT_API_KEY` (plus `OPAA_REPORT_BASE_URL`), `OPAA_OPENAI_API_KEY` bleibt ausschließlich der Anwendung vorbehalten, Dokumentation beider Secrets.

**Geliefert:** PR #278 stellt `daily_report.py`/`daily-report.yml` auf `OPAA_REPORT_API_KEY`/`OPAA_REPORT_BASE_URL` um, `ci.yml` bleibt unangetastet. Zusätzlich nebenbei behoben: ein Vorgabewert-Bug, bei dem eine leer gesetzte (statt fehlende) Repository-Variable den `os.environ.get`-Default für `OPAA_REPORT_MODEL` umgangen hätte (Regression aus #259, wäre erst beim ersten Aktivieren aufgetreten). Keine Abweichung vom Issue.

**Verifikation:** `.github/scripts/daily_report.py` liest heute `OPAA_REPORT_API_KEY` (Zeile 781) und dokumentiert den Grund inline: „damit sich [...] nicht aus dem Anwendungsschlüssel OPAA_OPENAI_API_KEY“ speist — Trennung bestätigt.

**Themen:** ci, projektsetup
