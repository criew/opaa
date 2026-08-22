# Issue #321 — feat(ci): Tagesreport auf Management Summary umstellen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, enhancement, size:M, ci
- PRs: #322 (2026-08-14)

**Laut Issue:** Der Tagesreport soll statt vier flacher Listen und Fließtext eine kompakte Management Summary zeigen: Linkleiste (Testumgebung, Repository, Issues, PRs, CI mit Statuspunkt), vier Kennzahlen-Kacheln, Epic-Abschnitte mit Fortschrittsbalken und modellgenerierten Stichpunkten, Sonstiges-Abschnitt, keine Detaillisten mehr. Fällt die Zusammenfassung aus, sollen Titel statt Stichpunkte erscheinen; bestehende Reports sollen rückwirkend im neuen Layout neu erzeugt werden.

**Geliefert:** PR #322 setzt den Umfang wie gefordert um: Linkleiste mit CI-Statuspunkt (grün/rot, zusätzlich Klartext im Tooltip aus Barrierefreiheitsgründen), Testumgebungs-URL über `--test-url`/`OPAA_REPORT_TEST_URL` konfigurierbar, vier Kennzahlen-Kacheln, Epic-Abschnitte mit Fortschrittsbalken und Stichpunkten (Modell liefert JSON statt Fließtext), Rückfall auf Titel bei Ausfall, Neu-Rendering aller Bestandsseiten. Zusätzlich 35 neue Tests in `test_daily_report.py` mit Mutationsnachweis (drei Assertionen entfernt, jeweils zugehörige Tests schlagen fehl). Deckt sich mit dem Issue, keine wesentliche Abweichung.

**Verifikation:** Nicht vertieft geprüft (reiner CI-Skript-Bereich, `.github/scripts/daily_report.py`), da unstrittig und mit Mutationsnachweis im PR belegt. Kein Widerspruch zu späteren Issues in diesem Chunk erkennbar (Issue #335 baut direkt auf dieser Struktur auf).

**Themen:** ci, tagesreport, doku
