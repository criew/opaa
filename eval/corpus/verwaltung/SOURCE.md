# Quelle: Domäne Verwaltung

| | |
|---|---|
| **Quelle** | vollständig synthetisch — kein Rohdaten-Snapshot einer Fremdquelle |
| **Lizenz** | CC0-1.0 |
| **Erzeugt am** | wird bei jedem Lauf des Generators neu geschrieben; kein Abrufdatum, weil kein Live-Zugriff stattfindet |
| **Generator** | [`../../generator/generate_verwaltung_corpus.py`](../../generator/generate_verwaltung_corpus.py) (Issue #1042) |
| **Dokumente** | 70 (`verwaltung-0001_*.md` … `verwaltung-0068_*.md`, ein Dokument je Amtsvorgang, plus zwei organisationsweite Dokumente `verwaltung-vertretungsregelung.md` und `verwaltung-geschaeftsverteilungsplan.md`, deren `id`-Werte `verwaltung-0069`/`verwaltung-0070` fortlaufend sind, deren Dateinamen aber nicht dem `NNNN_*`-Schema folgen) |
| **Sampling** | Kein Sampling: der Korpus besteht aus einer festen, im Generator hinterlegten Liste von zehn fiktiven Ämtern (`AEMTER`) und einer festen Anzahl Dokumenttypen je Amt — es gibt keine Grundgesamtheit, aus der gezogen wird |

## Warum kein Fremddatensatz

Anders als `comic-characters` (HuggingFace) und `city-landmarks` (Wikidata/GeoNames) hat diese
Domäne bewusst **keine** externe Quelle. Die Verwaltungssprache, die diese Domäne prüfen soll —
Satzungstexte, Gebührenordnungen, Dienstanweisungen, Formularhinweise, Amtssprache mit
Registerbruch zur Bürgersprache — lässt sich nicht aus einem offen lizenzierten, deutschsprachigen
Rohdatensatz mit der hier benötigten Fallklassen-Struktur (wörtlich auffindbare, aber
embeddingschwache Passagen; konfusionsfähige Kennungen; Fassungspaare; eine
Multi-Hop-Vertretungskette) gewinnen — sie wird deshalb konstruiert, nicht kuratiert (siehe
`docs/features/retrieval-benchmark.md`, Abschnitt 4, „Enthält die Fehlerbilder, die gemessen
werden sollen, konstruktiv statt zufällig"). `SOURCE.md` folgt dennoch derselben Konvention wie
bei den beiden anderen Domänen, damit die Herkunft jeder Domäne an derselben Stelle nachschlagbar
bleibt — die Antwort ist hier "keine Fremdquelle", nicht "unbekannt".

Die fiktive Gemeinde dieser Domäne heißt **"Kalkstadt"** — bewusst ein anderer, erfundener Ort als
"Rheinfurt" (`demo/corpus/`, das Vorbild für Stil und Aufbau, siehe
[`../../generator/README.md`](../../generator/README.md)). Eine kosmetische Verbesserung eines
Demo-Dokuments erzwingt dadurch nie eine Neuziehung dieses Messkorpus, und umgekehrt (siehe
Spezifikation, Abschnitt „Getrennt von der Demo").

## Wie diese Dateien entstanden sind

Erzeugt durch
[`eval/generator/generate_verwaltung_corpus.py`](../../generator/generate_verwaltung_corpus.py).
Kein Netzzugriff: Das Skript liest nur seine eigenen, im Quelltext hinterlegten Daten (`AEMTER`,
Textbausteine je Dokumenttyp). Zwei Läufe erzeugen byte-identische Ausgabe (per Vergleich der
SHA-256-Summen aller 70 Dateien über zwei aufeinanderfolgende Läufe geprüft, siehe
PR-Beschreibung von Issue #1042).

## Integritätsprüfung

```bash
cd eval/corpus/verwaltung
sha256sum -c MANIFEST.sha256
```

## Dokumenttypen und Fallklassen-Bezug

| Dokumentart (`dokumentart`) | Anzahl | Trägt vor allem Fallklasse (siehe `retrieval-benchmark.md`, Abschnitt 5) |
|---|---|---|
| `satzung` | 15 (10 Ämter, 5 davon als Fassungspaar 2023/2024) | (a) `literal_term_weak_embedding` — **nur die Kämmerei-Satzung** (`verwaltung-0038_verwaltungsgebuehrensatzung.md`) trägt § 3 „Gebührenbefreiung wegen Bedürftigkeit"; die übrigen 14 Satzungen haben an § 3 den thematisch nahen, aber begriffsfreien Konkurrenzparagraphen „Auskunftsrecht und Akteneinsicht" (`paragraph_auskunftsrecht`); (b) `exact_identifier` über § 3 vs. § 13; (c) `compound_word` über die Satzungstitel; (e) `metadata_filter` über die Fassungspaare |
| `gebuehrenordnung` | 10 (ein Dokument je Amt) | (a) — nur die Kämmerei-Gebührenordnung nennt „Gebührenbefreiung wegen Bedürftigkeit"; (c); alle referenzieren generisch § 3/§ 13 der zugehörigen Satzung |
| `dienstanweisung` | 23 (2 Nummern je Amt, Nr. 1 bei drei Ämtern als Fassungspaar 2023/2024) | (a) — nur die beiden Kämmerei-Dienstanweisungen; (b) `exact_identifier` über benachbarte Aktenzeichen (`<AMT>-DA-<Nr>/<Jahr>`); (d) `multi_hop` über den Verweis auf die Vertretungsregelung; (e) `metadata_filter` über die drei Fassungspaare |
| `formularhinweis` | 20 (2 Nummern je Amt) | (a) — nur die beiden Kämmerei-Formularhinweise; (b) `exact_identifier` über benachbarte Formularnummern (`Formular <AMT>-07`/`-08`) |
| `vertretungsregelung` | 1 (organisationsweit) | (d) `multi_hop` — trägt ausschließlich die Vertretungs-/Zuständigkeitshälfte (wer entscheidet ersatzweise), keine Sachaussage zu § 3/§ 13 einer konkreten Satzung; jede Satzung, Dienstanweisung und jeder Formularhinweis verweist hierher statt die Vertretung selbst zu wiederholen |
| `geschaeftsverteilungsplan` | 1 (organisationsweit) | (d) `multi_hop` — analog, für die Frage „wer ist fachlich zuständig" statt „wer vertritt"; ebenfalls ohne § 3/§ 13-Sachaussage |

Das vollständige Frontmatter-Schema (u. a. `dokumentart`, `fassung`, `stand_datum`, `gueltig_ab`,
`gueltig_bis`, `ersetzt`/`ersetzt_durch`, `aktenzeichen`, `schlagworte`) ist in jedem Dokument
selbst dokumentiert (YAML-Kopf) und in
[`../../generator/generate_verwaltung_corpus.py`](../../generator/generate_verwaltung_corpus.py)
(`FRONTMATTER_FIELDS`) als Quelle der Wahrheit gepflegt.

## Umfang und Größenverteilung

Tatsächlich gemessen (nicht geschätzt), Stand des letzten Generator-Laufs:

| | Bytes |
|---|---|
| Minimum | 6.697 |
| Median | 7.089 |
| Maximum | 9.797 |
| Gesamtgröße | ca. 518,4 KiB |

Zusammen mit den rund 1,9 MB (`comic-characters`) und 4,92 MB (`city-landmarks`) bleibt der
Gesamtkorpus mit rund 7,3 MB weiterhin deutlich unter der 25-MB-Prüfschwelle aus ADR-0011.

**Chunk-Zahl-Verifikation** (echter `TokenTextSplitter`-Lauf, `chunkSize=1000`,
`chunkOverlap=100` — Docker-freier Trockenlauf über `io.opaa.eval.VerwaltungChunkSizeDryRunTest`,
mirrors `CityLandmarksChunkSizeDryRunTest`): Minimum 3, Median 3, Maximum 4 Chunks je Dokument —
die Domänen-Vorgabe „mindestens 3 Chunks je Dokument" ist für alle 70 Dokumente erfüllt (0
Verletzungen). Anders als bei `city-landmarks` (Median 8) liegt der Median hier bewusst nahe an
der Mindestschwelle: Die Dokumenttypen dieser Domäne (Satzungsparagraphen, Dienstanweisungs-
abschnitte, Formularhinweis-Abschnitte) sind von Natur aus kürzer als enzyklopädische
Sehenswürdigkeiten-Absätze; ein größerer Sicherheitsabstand zur Mindestschwelle wäre nur durch
zusätzlichen, inhaltlich nicht mehr begründbaren Füll­text erkauft worden.

## Begriffs- und Kennungshäufigkeit im Korpus (verifiziert, nicht geschätzt)

Nachgezählt über alle 70 generierten Dateien (siehe PR-Beschreibung von #1074 für den
verwendeten Python-Einzeiler; jederzeit reproduzierbar über ein einfaches `grep -l` je Begriff im
Korpusverzeichnis):

| Begriff | Trefferdokumente | Welche |
|---|---|---|
| „Bedürftigkeit" | 6 von 70 | die Satzung, Gebührenordnung, beide Dienstanweisungen und beide Formularhinweise der Kämmerei (`verwaltung-0038` bis `verwaltung-0043`) |
| „Gebührenbefreiung" | 6 von 70 | dieselben sechs Kämmerei-Dokumente |
| „Bürgergeld" | 9 von 70 | die sechs Kämmerei-Dokumente sowie die beiden Formularhinweise von Sozialamt, Bürgeramt und Jugendamt, deren `alltagsfrage` unabhängig davon ebenfalls den Bürgergeld-Bezug thematisiert (reine Bürgersprache-Flavor-Texte, kein Bezug zu § 3 dieser Ämter — deren § 3 ist „Auskunftsrecht und Akteneinsicht", nicht die Bedürftigkeitsklausel) |
| „§ 3" | 68 von 70 | alle Amtsdokumente (nicht die zwei organisationsweiten Dokumente, die bewusst keine §-Sachaussage tragen — siehe unten) |
| „§ 13" | 68 von 70 | wie „§ 3" |

Damit ist die für die `literal_term_weak_embedding`-Fallklasse (Abschnitt 5a der Spezifikation)
nötige Seltenheit gegeben (Dokumenthäufigkeit 6/70 ≈ 0,086 statt der ursprünglich implementierten
70/70), während die Kennungs-Verwechslungsgefahr für `exact_identifier` (Abschnitt 5b) erhalten
bleibt: „§ 3" allein bleibt über 68 Dokumente hinweg mehrdeutig, aber nur in sechs davon steht
tatsächlich eine Bedürftigkeitsklausel — die anderen 62 sind für den `literal_term`-Fall
glaubwürdige, sachlich falsche Konkurrenzdokumente, nicht nur Rauschen.

## Bekannte Eigenschaften und Grenzen dieses Korpus

- **§ 3 und § 13 sind über alle Amts-Satzungen hinweg an denselben Paragraphennummern verankert,
  aber mit unterschiedlichem Regelungsgegenstand** — bewusst, für die `exact_identifier`-Fallklasse
  (siehe oben). Das bedeutet zugleich: Eine Frage, die nur „§ 3" ohne Amtsbezug nennt, trifft ohne
  weitere Eingrenzung auf bis zu zehn Satzungen (bzw. weitere Gebührenordnungen/Dienstanweisungen/
  Formularhinweise, die generisch auf „§ 3 der einschlägigen Gebührensatzung" verweisen); erst die
  Kombination mit der konkreten Gebühr oder dem Amt macht die Frage eindeutig beantwortbar. Nur in
  einem einzigen Amt (Kämmerei) behandelt § 3 tatsächlich eine Gebührenbefreiung — bei den übrigen
  neun ist § 3 das „Auskunftsrecht und Akteneinsicht" (siehe Tabelle oben). Das ist die gewollte
  Verwechslungsgefahr, kein Fehler.
- **Die Textschablonen sind über alle zehn Ämter strukturell identisch**, nur die Amts-, Gebühren-
  und Alltagsfrage-Begriffe variieren (dasselbe Muster wie bei `comic-characters` und
  `city-landmarks`, siehe dort). Das macht die Ground Truth aus dem Frontmatter berechenbar,
  staucht aber die Ähnlichkeits-Score-Verteilung — bei einer künftigen Golden-Dataset-Erstellung
  (Issue-Folge zu #1042, Schritt D der Spezifikation) gilt derselbe Kalibrierungshinweis wie in
  `eval/golden/README.md`, Abschnitt „Kalibrierungshinweis für #227/#228".
- **Gebührenbeträge sind deterministisch, aber nicht real.** `compute_fee()` berechnet einen Betrag
  aus Amtskürzel und Positionsindex, ohne Bezug zu tatsächlichen Kommunalgebühren — für
  Retrieval-Metriken ist das irrelevant, sollte aber niemand als reale Gebührenhöhe fehlinterpretieren.
- **Die Fassungspaare unterscheiden sich nur im Frontmatter (`fassung`, `stand_datum`,
  `gueltig_ab`, `gueltig_bis`, `ersetzt`/`ersetzt_durch`), nicht im Fließtext der §§.** Für die
  `metadata_filter`-Fallklasse ist das ausreichend — die Ground Truth für „welche Fassung gilt am
  Stichtag X" liegt vollständig im Frontmatter. Ein inhaltlicher Unterschied zwischen den Fassungen
  (z. B. eine geänderte Gebührenhöhe) ist bewusst nicht modelliert, um die beiden Fassungen für
  jede rein inhaltliche Frage ununterscheidbar zu halten — die Unterscheidung muss über die
  Fassungs-Metadaten erfolgen, nicht über einen zufällig im Text gefundenen Unterschied.
- Wie bei `comic-characters` und `city-landmarks`: aller Fließtext ist vom Generator selbst
  formuliert, es wird kein Text aus einer Fremdquelle übernommen (hier ohnehin gegenstandslos,
  da keine Fremdquelle existiert).

## Overfitting-Risiko

Siehe `docs/features/retrieval-benchmark.md`, Abschnitt 4, „Ehrliche Einschränkung:
Benchmark-Overfitting" — die dortige Einschränkung gilt unverändert für diesen Korpus: Es gibt für
diese Domäne keine echten Nutzerfragen, die Golden-Fälle (Issue-Folge zu #1042) werden von
denselben Personen erdacht, die Korpus und Pipeline bauen. Ein Verfahren, das auf dieser Domäne
gewinnt, gewinnt nachweislich gegen die hier konstruierten Annahmen über Verwaltungsanfragen —
nicht nachweislich gegen echte Verwaltungsanfragen. Siehe auch
[`MAINTENANCE.md`](MAINTENANCE.md).
