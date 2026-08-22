# Issue #225 — feat(eval): Korpus-Generator für die Domäne Comichelden
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, size:M, evaluation
- PRs: #249 (2026-08-02)

**Laut Issue:** Deterministischer Python-Generator, der aus dem eingefrorenen HuggingFace-Datensatz `jrtec/Superheroes` (CC0-1.0, ~1.450 Entitäten, kein Sampling) je Comicfigur ein Markdown-Dokument mit YAML-Frontmatter und selbst formuliertem Fließtext erzeugt, damit ein Dokument in der produktiven Chunking-Pipeline genau einen Chunk ergibt. Vorgaben u. a.: `MANIFEST.sha256`, `SOURCE.md`, Byte-Identität bei wiederholtem Lauf, max. 4 KB je Dokument, Gesamtkorpus unter 5 MB, keine Übernahme der langen Freitextfelder der Quelle.

**Geliefert:** `eval/generator/generate_corpus.py` erzeugt 1.448 Dokumente in `eval/corpus/comic-characters/` (größtes 2.573 Bytes, Gesamtkorpus ~1,88 MB), `MANIFEST.sha256` und `SOURCE.md` vorhanden, Determinismus per `diff -r` verifiziert. Nach Review verschärft: Byte-Grenze von 4 KB auf 3.000 Bytes gesenkt (Tokengrenze ist die eigentliche Invariante, nicht die Byte-Grenze), dazu neues ADR-0010 zur Ein-Chunk-Invariante, `height_cm`-Normalisierung erweitert, `teams` als echte YAML-Sequenz statt kommagetrennter String. Feldname `superpowers` statt der im Issue genannten „Liste der gesetzten Fähigkeiten" (an der Spezifikation orientiert). Zwei Prosa-Bugs (Verb-Kongruenz, a/an-Heuristik) während der Stichprobenprüfung mitbehoben.

**Verifikation:** `eval/corpus/comic-characters/` enthält im Worktree 1.450 Einträge (inkl. `MANIFEST.sha256`, `SOURCE.md`), `eval/generator/generate_corpus.py` existiert weiterhin. ADR-0010 liegt unter `docs/decisions/`.

**Themen:** eval, retrieval, korpus, python-tooling, doku
