# Issue #485 — feat(indexing): Zeitplan je Bibliothek für Indizierungsläufe
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend
- PRs: #705 (2026-08-21)

**Laut Issue:** Ein an-/abschaltbarer Zeitplan je Konnektorbibliothek sollte eingeführt werden, mit Anzeige „letzter/nächster Lauf" auf der Detailseite, einer Antwort auf verteilte Ausführung (Lock/Leader) und mindestens vorbereiteten Vorrangregeln. Der Umfang war laut Issue „grob, vor Umsetzung zu verfeinern".

**Geliefert:** Wie im Kern gefordert, mit zwei bewussten Zuschnittsentscheidungen: feste Intervallstufen (stündlich/täglich/wöchentlich/aus) statt freier Cron-Eingabe, intern als Cron-Ausdruck gespeichert; kein eigener Leader-/Lock-Mechanismus — die bestehende DB-Sperre `uk_indexing_jobs_library_running` verhindert Doppelstarts bereits, ein Tick auf eine laufende Bibliothek wird übersprungen und protokolliert. Vorrangregeln wurden nicht vorbereitet, sondern laut PR bewusst fallengelassen. Anzeige „nächster geplanter Lauf" und ein Warnhinweis bei zwei aufeinanderfolgenden fehlgeschlagenen Läufen (ohne automatische Deaktivierung) sind umgesetzt.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/LibraryIndexingScheduler.java` und `LibraryScheduleCodec.java` existieren; `frontend/src/components/EditLibraryScheduleDialog.tsx` existiert. Zeitzone ist Serverzeit, nicht konfigurierbar — im PR ausdrücklich als Zuschnitt benannt, nicht als Lücke versteckt.

**Themen:** backend, frontend, indexing, scheduling, spaces
