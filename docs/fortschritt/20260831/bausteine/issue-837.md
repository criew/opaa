# Issue #837 — fix(indexing): storeChunks vergibt bei identischen Chunk-Texten doppelte chunk_index-Werte
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, backend, size:S
- PRs: keine

**Laut Issue:** Teil von Epic #826, Phase 1. `FileProcessingService.storeChunks` ermittelte den `chunk_index` per `chunks.indexOf(chunk)` — bei identischen Chunk-Texten (Duplikat-Absätze) liefert das für beide Vorkommen denselben Index (Korrektheitsfehler) und ist zudem O(n²).

**Geliefert:** Kein eigener PR verknüpft, aber im heutigen Code besteht der beschriebene Fehler nicht mehr: `storeChunks` trägt `chunk_index` über die Iterationsposition (`metadata.put("chunk_index", index)`), nicht über `indexOf`. Der Fix ist vermutlich als Nebeneffekt der Ollama-Embedding-Parallelisierung (#735, die `storeChunks` auf Sub-Batches mit expliziter Indexführung umgebaut hat) bereits vor Ticket-Erstellung erledigt gewesen, oder wurde in einem nicht separat verlinkten Commit mitgezogen — nicht abschließend rekonstruierbar aus den vorliegenden Daten.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/FileProcessingService.java:613` verwendet die Schleifenvariable `index` statt `indexOf`; kein Aufruf von `chunks.indexOf` im Umfeld gefunden.

**Themen:** indexing, bugfix, chunking
