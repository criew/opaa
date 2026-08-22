# Issue #279 — feat(ci): Anthropic als Anbieter für die Report-Zusammenfassung unterstützen
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, size:S, ci
- PRs: #281 (2026-08-02)

**Laut Issue:** Die Report-Zusammenfassung sprach nur das OpenAI-Chat-Completions-Format an. Für Anthropic-Schlüssel wird ein anderes Anfrageformat gebraucht (anderer Pfad, `x-api-key` statt Bearer, eigenes `system`-Feld, andere Antwortstruktur, `max_tokens` erforderlich). Gefordert: Anbieter über `OPAA_REPORT_PROVIDER` wählbar, ohne gesetzte Variable Erkennung am Schlüsselpräfix (`sk-ant-` → Anthropic), Vorgabemodell je Anbieter, Fehlschläge weiterhin ohne Abbruch, Dokumentation beider Anbieter.

**Geliefert:** PR #281 setzt genau das um — `OPAA_REPORT_PROVIDER` mit Präfixerkennung als Fallback, anbieterabhängiges Vorgabemodell/-endpunkt. Zusätzlich ergänzt: `HTTPError`-Behandlung protokolliert jetzt den Fehlertext des Anbieters statt nur „Zusammenfassung fehlgeschlagen“. Laut PR-Body gegen die echte API getestet (Anthropic-Haiku-Modell) und Anbietererkennung in fünf Fallkombinationen geprüft. Keine Abweichung vom Issue.

**Verifikation:** `.github/scripts/daily_report.py` liest `OPAA_REPORT_PROVIDER` (Zeile 665) mit Fallback-Erkennung — Umsetzung im aktuellen Code vorhanden.

**Themen:** ci, projektsetup
