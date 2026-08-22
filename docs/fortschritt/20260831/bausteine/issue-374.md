# Issue #374 — fix(indexing): Chunking ohne Überlappung trennt Definitionen von ihrer Überschrift
- Geschlossen: 2026-08-14 (completed)
- Labels: bug, backend, size:S
- PRs: #402 (2026-08-14)

**Laut Issue:** `ChunkingService` nutzte `TokenTextSplitter` ohne Überlappung, obwohl die Dokumentation ~10% Überlappung behauptete. Schadensfall: Überschrift/Definition landen getrennt in zwei Chunks, beide Hälften sind als Beleg schlechter. Verlangt: Überlappung konfigurierbar mit begründetem Standardwert, Wirkung gegen den Retrieval-Harness aus #224 messen, Spezifikation nachziehen.

**Geliefert:** `TokenTextSplitter` aus Spring AI 2.0.0 kennt gar keinen Überlappungsparameter — musste selbst gebaut werden. Neue Klasse `OverlappingTokenTextSplitter`, neue Property `opaa.indexing.chunk-overlap` (Default 100 Token). Messung wie verlangt durchgeführt, aber mit ehrlichem Negativbefund: Der Evaluierungskorpus unterliegt der Ein-Chunk-Invariante (ADR-0010) — jedes Dokument ergibt genau einen Chunk, eine Überlappung kann dort strukturell nichts bewirken. Alle drei Messläufe (0/100/200 Token) liefern identische Kennzahlen. Der Standardwert 100 ist deshalb **gesetzt, nicht gemessen** — offen als Punkt in `eval/README.md` festgehalten. Nebenbefund: Harness war zuvor gar nicht lauffähig (fehlendes `@ActiveProfiles("dev")`), im selben PR behoben. Reproduktionsnachweis mit rotem/grünem Testlauf erbracht.

**Verifikation:** `OverlappingTokenTextSplitter.java` existiert im Worktree unter `backend/src/main/java/io/opaa/indexing/`.

**Themen:** retrieval, chunking, indexierung, belegbarkeit, backend, evaluierung
