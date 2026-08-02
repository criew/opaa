# ADR-0010: Absicherung der Ein-Chunk-Invariante des Evaluierungskorpus

## Status

Vorgeschlagen

## Kontext

Der gesamte Evaluierungskorpus (`eval/corpus/<domäne>/`, siehe ADR-0011 und
`docs/features/search-quality-evaluation.md`) beruht auf einer einzigen Invariante: Ein generiertes
Dokument entspricht beim Indizieren genau einem Chunk. Nur dadurch ist ein gefundener Chunk
eindeutig einer Entität zuordenbar — die Voraussetzung für die Ground Truth des Golden Dataset
(#226) und für belastbare Retrieval-Metriken (Hit Rate, MRR, nDCG).

Diese Invariante koppelt zwei Komponenten, die sonst nichts miteinander verbindet:

1. den Python-Generator unter `eval/generator/` (Issue #225, bewusst außerhalb von Gradle und CI,
   siehe ADR-0011, Entscheidung 2), und
2. `opaa.indexing.chunk-size` im Backend, konfiguriert für Spring AIs `TokenTextSplitter`.

Der `TokenTextSplitter` zählt **Tokens** in der `cl100k_base`-Kodierung, nicht Zeichen oder Bytes,
und kennt keine Overlap-Konfiguration — ein Dokument, das die konfigurierte `chunkSize` an Tokens
erreicht oder überschreitet, wird unweigerlich zu mehr als einem Chunk. Der ursprüngliche Generator
(PR #249) prüfte stattdessen eine Byte-Obergrenze (4 KB, aus den Abnahmekriterien von Issue #225
übernommen). Das ist keine Absicherung der eigentlichen Invariante, sondern nur eine Annäherung:
Bytes und Tokens korrelieren über die Tokendichte des jeweiligen Texts, und diese Dichte ist nicht
konstant. Beim Code-Review zu PR #249 wurde das mit gemessenen Zahlen belegt: Bei der im
Comichelden-Korpus tatsächlich beobachteten Höchstdichte (~0,316 Tokens/Byte) kippt die
1000-Token-Marke bereits bei ~3.164 Bytes — deutlich unterhalb der ursprünglichen 4-KB-Grenze. Für
Comichelden hält die Invariante heute trotzdem (größtes Dokument 2.573 Bytes), aber die künftigen
drei Domänen aus #234 (Filme, Reiseziele, Tiere) bringen absehbar dichteren Text — mehr Eigennamen,
Zahlen und Nicht-ASCII-Zeichen —, wodurch eine feste Byte-Grenze aus heutiger Beobachtung ohne
Vorwarnung falsch werden kann. Ein Golden-Dataset-Eintrag, der auf einer stillschweigend in zwei
Chunks zerfallenen Entität beruht, ist nicht offensichtlich falsch — er produziert nur leise
verzerrte Metriken.

## Entscheidung

Drei Optionen standen zur Wahl:

- **(a) Der Generator zählt Tokens selbst** (`tiktoken`, `cl100k_base`), mit einem
  `MAX_DOCUMENT_TOKENS`-Limit anstelle einer Byte-Grenze. Das wäre die genauere Prüfung an der
  Quelle — aber sie führt eine zweite Tokenizer-Implementierung ein, die von der tatsächlichen
  Backend-Konfiguration entkoppelt bleibt: Ändert sich das Embedding-Modell oder der Splitter im
  Backend künftig auf eine andere Tokenisierung, veraltet die Prüfung im Generator lautlos, während
  Option (c) automatisch mit der echten Konfiguration mitzieht. Zusätzlich kostet es die
  Standardbibliothek-only-Eigenschaft, die ADR-0011 für den Generator bewusst festgelegt hat.
  **Verworfen.**
- **(b) Der Generator behält eine konservative Byte-Vorabprüfung** (`MAX_DOCUMENT_BYTES`, aktuell
  3.000 — unterhalb des gemessenen Kipppunkts von ~3.164 Bytes bei der höchsten im Korpus
  beobachteten Tokendichte). Sie bricht die Generierung sofort ab, wenn ein Dokument spürbar zu
  groß gerät, ohne dass dafür ein Retrieval-Testlauf nötig ist, und hält den Generator
  standardbibliothek-only. Ist aber nur eine Annäherung, kein Beweis — siehe Kontext oben.
  **Übernommen, aber allein nicht ausreichend.**
- **(c) Der Java-Retrieval-Harness (#227) prüft die Invariante**: Dort läuft der echte, produktiv
  konfigurierte `TokenTextSplitter` gegen den eingefrorenen Korpus. Der Harness zählt die
  erzeugten Chunks je Quelldokument (über die Chunk-Metadaten `document_id`/`file_name`) und
  schlägt fehl, wenn irgendein Dokument mehr als einen Chunk ergibt — die einzige Prüfung, die
  tatsächlich beweist, dass die Invariante hält, weil sie denselben Splitter mit derselben
  Konfiguration wie die Produktion verwendet. Allein genommen (ohne (b)) verworfen, weil dann jede
  Korpus-Regenerierung erst nach einem vollständigen Testcontainers-Lauf (pgvector + Ollama) einen
  möglichen Fehler zeigt, statt sofort beim `python generate_corpus.py`-Lauf — für einen
  Python-Entwickler ohne Backend-Umgebung ein unnötig teurer Feedback-Zyklus für einen Fehler, der
  sich günstig vorab eingrenzen lässt. **Übernommen als die eigentlich beweiskräftige Prüfung.**

**Gewählt wird die Kombination aus (b) und (c)**: eine billige, bewusst konservative
Vorabprüfung im Generator (b), die den überwiegenden Teil realistischer Regressionen sofort
abfängt, plus die einzig wirklich beweiskräftige Prüfung dort, wo der echte Splitter läuft (c).
Option (a) — Tokens im Generator selbst zählen — bleibt verworfen: Sie würde eine zweite,
von der Produktionskonfiguration entkoppelte Tokenizer-Implementierung einführen, ohne (c)
überflüssig zu machen (ein grüner `tiktoken`-Check im Generator bewiese immer noch nicht,
dass der tatsächlich konfigurierte Backend-Splitter dasselbe Ergebnis liefert).

**Was bei einer Änderung von `opaa.indexing.chunk-size` passiert:** Die Byte-Grenze im Generator
ist nicht automatisch an `chunk-size` gekoppelt — sie ist eine manuell gepflegte, konservative
Konstante. Wird `chunk-size` künftig geändert (kleiner oder größer), muss sie erneut gegen die
dann gemessene Tokendichte des Korpus überprüft und ggf. angepasst werden; das ist keine
automatische Ableitung, sondern ein bewusster, reviewter Schritt, denselben Charakter wie eine
Baseline-Aktualisierung (siehe ADR-0011, Entscheidung 5). Der eingefrorene Korpus selbst
(Markdown-Dateien, Manifest) ändert sich durch eine `chunk-size`-Änderung nicht — nur ob die
Ein-Chunk-Invariante für ihn noch hält, was ausschließlich der Java-Harness in #227 verbindlich
beantwortet. Schlägt der Harness nach einer `chunk-size`-Änderung fehl, ist das kein Korpus-Bug,
sondern der erwartete Signalweg: Baseline und `chunk-size` sind gekoppelt (ADR-0011, Konsequenzen),
und eine Änderung an einem der beiden erfordert einen bewussten neuen Baseline-Lauf.

## Konsequenzen

**Einfacher:**

- Die meisten Regressionen (ein Dokument wird durch dichteren Text spürbar größer) fallen sofort
  beim Generator-Lauf auf, ohne Backend-Infrastruktur.
- Die eigentliche Garantie hängt an genau einer Stelle (#227), die mit der Produktionskonfiguration
  mitzieht, statt an einer zweiten, potenziell driftenden Tokenizer-Implementierung im Generator.
- Der Generator bleibt frei von Laufzeit-Abhängigkeiten außerhalb der Python-Standardbibliothek.

**Schwieriger:**

- Zwei Prüfstellen statt einer bedeuten zwei Stellen, die bei einer `chunk-size`-Änderung im Blick
  behalten werden müssen; die Byte-Grenze im Generator veraltet lautlos, wenn sie nach einer
  `chunk-size`-Änderung nicht neu bewertet wird — sie ist eine Annäherung, kein automatisch
  mitziehender Wert.
- Ein grüner Generator-Lauf beweist die Invariante nicht; erst ein grüner Lauf von #227 tut das.
  Das muss in der Dokumentation beider Werkzeuge sichtbar bleiben (siehe
  `eval/generator/README.md`), damit niemand die Byte-Prüfung fälschlich als hinreichend liest.
- Für die drei weiteren Domänen aus #234 muss die Byte-Grenze jeweils neu anhand der dort
  gemessenen Tokendichte bestimmt werden, statt den hier für Comichelden ermittelten Wert (3.000)
  unbesehen zu übernehmen.

## Randnotiz

Die „4 KB"-Grenze in den Abnahmekriterien von Issue #225 ist die ursprüngliche Quelle der
Byte/Token-Ungenauigkeit, die dieses ADR behebt. Sie sollte dort und im Nachfolge-Issue #234
korrigiert werden — entweder als Token-Budget oder mit einem expliziten Hinweis, dass es sich um
eine konservative Annäherung handelt, keine Garantie.
