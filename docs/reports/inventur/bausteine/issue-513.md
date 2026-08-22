# Issue #513 — feat(indexing): Übersprungene Dokumente eines Laufs mit Grund in der Oberfläche anzeigen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #604 (2026-08-20)

**Laut Issue:** Beim Indizieren eines RSS-Feeds auf der Testinstanz wurden 19/20 Einträge übersprungen (Bot-Schutz), aber die Oberfläche zeigte nur die Zahl ohne Grund. Gefordert: Der Indizierungsstatus sollte eine Liste übersprungener/abgewiesener Inhalte mit kategorisiertem, deutschem Grund führen, einklappbar auf der Detailseite, ohne rohe Challenge-URLs.

**Geliefert:** Wie gefordert, mit größerem Umfang als im Issue skizziert: Ein allgemeines Protokollformat je Lauf, einheitlich für alle nicht-UPLOAD-Quellentypen (nicht nur RSS), mit Kategorien `REJECTED`/`UNREACHABLE`/`UNSUPPORTED_FORMAT`/`ALLOWLIST`/`ERROR`, gekappt bei 500 Ereignissen je Lauf. Neue Tabelle `indexing_run_events`, neuer Endpunkt `GET .../indexing/runs`. Zusätzlich: Aufbewahrung der letzten 10 Läufe je Bibliothek samt automatischer Bereinigung älterer Läufe — eine im Issue nicht geforderte, aber sinnvolle Ergänzung.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/IndexingRunEvent.java`, `IndexingRunEventRecorder.java` und `IndexingRunEventRepository.java` existieren im heutigen Code.

**Themen:** backend, frontend, feeds, indexing, transparenz
