# Issue #501 — fix(indexing): Hängengebliebene RUNNING-Jobs sperren ihre Bibliothek dauerhaft
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend
- PRs: #649 (2026-08-20)

**Laut Issue:** Eine `indexing_jobs`-Zeile kann dauerhaft `RUNNING` bleiben (verworfene Async-Aufgabe, abgestürzter Prozess) und sperrt seit #478 ihre Bibliothek dauerhaft, ohne Weg zur Auflösung in der Oberfläche. Gefordert: Bereinigung verwaister `RUNNING`-Jobs beim Neustart, Überdenken der `DiscardPolicy`, Test für das Neustart-Szenario.

**Geliefert:** Wie gefordert. `IndexingJobRecoveryScheduler` markiert beim Anwendungsstart jede noch `RUNNING`-Zeile als `FAILED`; ein periodischer Sweep (alle 15 Minuten, konfigurierbare Altersgrenze `opaa.indexing.stale-job-timeout`, Default 4h) fängt zusätzlich Läufe ohne Neustart ab. `indexingTaskExecutor` nutzt jetzt `AbortPolicy` statt `DiscardPolicy` (analog zu `uploadTaskExecutor` seit #589); eine abgelehnte Aufgabe setzt den Job sofort auf `FAILED` und liefert 503. Reproduktionsnachweis mit rotem/grünem Testlauf im PR dokumentiert. Ein verwandter, aber eigenständiger Review-Befund (`deleteLibrary` bei laufendem Job) wurde bewusst als separates Folge-Issue ausgegliedert statt hier mitgelöst.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/IndexingJobRecoveryScheduler.java` existiert; `docs/deployment.md` dokumentiert `OPAA_INDEXING_STALE_JOB_TIMEOUT`.

**Themen:** backend, bugfix, indexing, betrieb
