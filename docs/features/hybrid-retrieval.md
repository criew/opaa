# Hybride Suche mit Reranking

> **Status: umgesetzt.** Jedes Arbeitspaket dieser Spezifikation ist gebaut; der jeweils gebaute
> Stand steht als Kasten am Kopf des zugehörigen Abschnitts. Ausgenommen ist allein das
> [Latenz-/Hardwareprofil](#arbeitspaket-latenz-hardwareprofil), das der Maintainer zurückgestellt
> hat. Die Spezifikation bleibt Begründung und Zuschnitt; den jeweils gebauten Ablauf beschreibt
> [Retrieval-Algorithmus (Ist-Stand)](./retrieval-algorithm.md).
>
> Diese Spezifikation setzt die Maintainer-Entscheidungen um, die aus den Recherchedokumenten
> [Retrieval-Strategien (Tech-Report)](../discussions/discussion-retrieval-strategien.md) und
> [Retrieval-Roadmap](../discussions/discussion-retrieval-roadmap-opaa.md) hervorgegangen sind — im
> Wesentlichen deren **Phase 1**. Die dort offen gelassenen Fragen (Aufhebung der #938-Zurückstellung,
> GPU-Annahme, Suchspeicher) sind entschieden und stehen unten als Festlegung, nicht als Abwägung. Was
> hier nicht steht, ist damit nicht entschieden; die Roadmap bleibt die Quelle für die späteren Phasen.

**Themenbereich A** der [Produktvision](../VISION.md), **Phase 1**. Diese Spezifikation ist der
Umsetzungsschnitt zweier Punkte, die [Wissensschicht und Retrieval](./data-indexing-rag.md) als Zielbild
bereits mit „ja" beantwortet hat — [Hybride Suche](./data-indexing-rag.md#hybride-suche) und
[Reranking](./data-indexing-rag.md#reranking) — und die vor diesem Epic im Code nicht existierten
(der jetzige Ablauf steht in [Retrieval-Algorithmus (Ist-Stand)](./retrieval-algorithm.md)). Sie
ersetzt keines der beiden Dokumente:
`data-indexing-rag.md` bleibt Quelle der Wahrheit für Zielbild und Stellschrauben-Tabelle,
`retrieval-algorithm.md` für den jeweils gebauten Ablauf. Gebaut sind inzwischen alle Arbeitspakete:

- **Arbeitspaket 1** — Pipeline als benannte Stufen mit Erklärprotokoll
  ([#1046](https://github.com/criew/opaa/issues/1046))
- **Arbeitspaket 2a** — Volltextspalte, GIN-Index, wiederaufnehmbarer Backfill
  ([#1047](https://github.com/criew/opaa/issues/1047))
- **Arbeitspaket 2** — lexikalischer Suchpfad mit Kennungsschutz und Rechtefilter
  ([#1048](https://github.com/criew/opaa/issues/1048))
- **Arbeitspaket 3** — Aufnahme dieses Pfads in die RRF-Fusion
  ([#1049](https://github.com/criew/opaa/issues/1049)); **damit ändert sich zum ersten Mal die
  Endauswahl**, entsprechend sind die Pipeline-Baselines aller drei Domänen neu gezogen
- **Arbeitspaket 4** — Reranking als Modellrolle mit eigenem Schalter, **per Voreinstellung aus**
  ([#1050](https://github.com/criew/opaa/issues/1050))
- **Die Administrationsseite „Suche & Indexierung"** samt Diagnosepfad
  ([#1053](https://github.com/criew/opaa/issues/1053)) und ihren Berechtigungs-Leitplanken
  ([#1052](https://github.com/criew/opaa/issues/1052))

Zurückgestellt ist allein das [Latenz-/Hardwareprofil](#arbeitspaket-latenz-hardwareprofil).

---

## Motivation

Die Grenze ist nicht vermutet, sondern gemessen. In
[#938](https://github.com/criew/opaa/issues/938) fragt ein Demo-Konto nach der Gebührenbefreiung wegen
Bedürftigkeit. Die maßgebliche Rechtsgrundlage — § 3 der Verwaltungsgebührensatzung — enthält die
Anfragebegriffe „Befreiung" und „Bedürftigkeit" **wörtlich** und rankt trotzdem auf Rang 50 der
Kandidatenliste, hinter thematisch fremder Konkurrenz. Für ein Konto, das nur die Satzungsbibliothek
lesen darf, endet das nicht mit einer schlechteren Antwort, sondern mit „kann ich nicht beantworten" —
bei vorhandenem Leserecht auf genau die richtige Quelle.

Keine der gebauten Auswahlmechaniken kann das heilen. `top-k`, MMR, Teilfragen-Zerlegung und
Dokument-Vervollständigung ordnen nur, **was die Vektorsuche liefert**; ein Rückstand von 42 Rängen ist
keine Ordnungsfrage. Der passende Mechanismus ist eine zweite, lexikalische Suchkomponente — sie hätte
diesen Fall trivial getroffen — und darüber ein Reranker, der die Kandidatenmenge nicht nach
Vektorabstand, sondern nach tatsächlicher Passung zur Frage sortiert.

Der zweite Antrieb ist struktureller Natur. Der heutige Orchestrator ist über sieben Ausbauschritte
(#912 bis #940) gewachsen und trägt jeden davon als Sonderfall. Ein weiterer Suchpfad und eine weitere
Bewertungsstufe als achter und neunter Sonderfall wären der Punkt, an dem niemand mehr erklären kann,
warum ein bestimmtes Dokument in einer Antwort steht — und genau diese Erklärbarkeit ist in der
Verwaltung kein Komfort, sondern die Betriebsvoraussetzung. Deshalb steht das Pipeline-Refactoring am
Anfang und nicht am Ende.

---

# Teil I — Begriffe

Dieser Teil richtet sich an Leser mit Informatikhintergrund, aber ohne RAG-Vorwissen. Er erklärt die
fünf Begriffe, ohne die der technische Teil nicht lesbar ist. Wer sie kennt, springt zu
[Teil II](#teil-ii--technisches-zielbild).

## Was die Suche in OPAA überhaupt tut

OPAA beantwortet Fragen nicht aus dem Gedächtnis des Sprachmodells, sondern sucht zuerst passende
Textabschnitte im Dokumentbestand und lässt das Modell die Antwort **aus diesen Abschnitten**
formulieren, mit Beleg. Daraus folgt der Satz, der alles Weitere trägt:

> **Die Suchqualität deckelt die Antwortqualität.** Was die Suche nicht findet, kann das Modell nicht
> belegen — es kann es höchstens erfinden.

Dokumente sind zu lang, um sie am Stück zu durchsuchen. Sie werden beim Indexieren in **Chunks**
zerlegt — Abschnitte von heute rund 1000 Token — und jeder Chunk wird einzeln gefunden. Wenn im Folgenden
von „Treffern" die Rede ist, sind immer Chunks gemeint, nie ganze Dokumente.

## Die zwei Arten zu suchen, und warum keine allein genügt

**Semantische Suche** (das, was OPAA heute tut). Ein Einbettungsmodell übersetzt jeden Text in einen
Zahlenvektor, so dass bedeutungsähnliche Texte nahe beieinander liegen. Die Frage wird ebenso übersetzt,
und gesucht werden die nächstgelegenen Chunk-Vektoren.

- **Stärke:** Sie überbrückt Vokabellücken. Wer nach „Führerschein" fragt, findet ein Dokument, das
  durchgehend von „Fahrerlaubnis" spricht — für einen Bestand in Amtssprache, der von Bürgern in
  Alltagssprache befragt wird, ist das die entscheidende Fähigkeit.
- **Schwäche:** Exakte Kennungen. „§ 35 BauGB" und „§ 34 BauGB" liegen im Vektorraum praktisch
  aufeinander, trennen rechtlich aber Außen- und Innenbereich — also zwei völlig verschiedene Antworten.
  Ein Aktenzeichen wie „4 K 1023/24.NW" zerfällt im Tokenizer in bedeutungsarme Fragmente; der Vektor
  „bedeutet" danach ungefähr nichts.

**Lexikalische Suche** (klassische Volltextsuche, in der Literatur meist als BM25). Sie zählt
Wortübereinstimmungen zwischen Frage und Text, gewichtet nach Seltenheit: Ein Wort, das in fünf von
50 000 Chunks vorkommt, wiegt schwerer als eines, das überall steht.

- **Stärke:** Genau das, woran die semantische Suche scheitert — Paragrafen, Aktenzeichen,
  Erlassnummern, Eigennamen, Fachtermini. Sie braucht kein Modell, kein Training und keine GPU.
- **Schwäche:** Genau das, was die semantische Suche kann. Wer „Führerschein" eingibt, findet ein
  Dokument über „Fahrerlaubnis" **nicht** — kein gemeinsames Wort, kein Treffer.

Die beiden Verfahren versagen an **komplementären** Stellen. Deshalb ist ihre Kombination — die
**Hybrid-Suche** — seit Jahren Branchenkonsens, und deshalb wiegt sie in der Verwaltung schwerer als in
generischen Anwendungen: Eine typische Verwaltungsfrage mischt beides in einem Satz („Was kostet die
Befreiung nach § 3 der Gebührensatzung?" — ein Alltagsbegriff und eine exakte Kennung).

## Fusion: zwei Ergebnislisten, eine Auswahl

Wenn zwei Suchverfahren nebeneinander laufen, liefern sie zwei Ranglisten. Die naheliegende Idee, ihre
Bewertungszahlen zu addieren, funktioniert nicht: Eine Kosinus-Ähnlichkeit von 0,62 und ein
Volltext-Rangwert von 0,31 sind keine vergleichbaren Größen, sie messen Verschiedenes auf verschiedenen
Skalen. Ein Vergleich solcher Zahlen war in
[#912](https://github.com/criew/opaa/issues/912) bereits einmal die Wurzel eines Fehlerbilds.

**Reciprocal Rank Fusion (RRF)** löst das, indem sie die Zahlen wegwirft und nur die **Ränge** benutzt.
Jeder Treffer bekommt aus jeder Liste, in der er vorkommt, den Beitrag `1 / (60 + Rang)`; die Beiträge
werden addiert. Der Wert 60 ist eine Dämpfungskonstante aus der Ursprungsarbeit (Cormack et al. 2009)
und sorgt dafür, dass der Abstand zwischen Rang 1 und 2 nicht alles dominiert.

```
Vektorliste      Volltextliste        RRF-Summe
1. Chunk A       1. Chunk C           C: 1/61 + 1/62 = 0,0325   ← gewinnt
2. Chunk B       2. Chunk A           A: 1/61 + 1/63 = 0,0323
3. Chunk C       7. Chunk B           B: 1/63 + 1/67 = 0,0308
```

Die Aussage dahinter ist einfach: **Ein Treffer, der in beiden Listen weit oben steht, gewinnt gegen
einen, der nur in einer Liste weit oben steht.** Chunk C ist in keiner der beiden Listen der beste
Treffer und wird trotzdem Erster — weil beide Verfahren unabhängig voneinander auf ihn zeigen.

OPAA hat RRF bereits gebaut, allerdings für einen anderen Zweck: Es fusioniert heute die Ergebnisse
mehrerer **Teilfragen** derselben Nutzerfrage (siehe
[Teilfragen-Zerlegung](./data-indexing-rag.md#teilfragen-zerlegung-und-query-reformulierung-multi-query-retrieval-923)).
Der Volltextpfad kommt als eine weitere Eingangsliste hinzu — derselbe Mechanismus, eine Liste mehr.

## Reranking: der zweite Blick

Die fusionierte Liste ist auf **Abdeckung** optimiert: Sie soll die richtige Fundstelle irgendwo unter
den ersten 50 haben. Was an das Sprachmodell übergeben wird, sind aber nur die besten 8 — und dafür
braucht es **Präzision**.

Der Unterschied liegt darin, wie bewertet wird. Bei der Vektorsuche werden Frage und Chunk **getrennt**
in Vektoren übersetzt; verglichen werden nur die beiden fertigen Zahlenreihen. Das Modell hat die Frage
nie zusammen mit dem Chunk gesehen. Ein **Cross-Encoder** tut genau das: Er bekommt Frage und Chunk als
ein einziges Eingabepaar und gibt eine Passungszahl aus. Er kann dadurch erkennen, ob der Text die Frage
tatsächlich **beantwortet**, und nicht nur, ob er vom selben Thema handelt.

Der Preis ist die Rechenzeit. Ein Vektorvergleich ist ein Skalarprodukt und praktisch kostenlos; ein
Cross-Encoder-Durchlauf ist ein Modellaufruf und muss für **jeden** Kandidaten einzeln passieren. Deshalb
ist ein Cross-Encoder als Erststufe über einer Million Chunks unbrauchbar und als **zweite** Stufe über
50 Kandidaten sinnvoll — und deshalb steht er im Ablauf hinter der Fusion, nicht davor.

Die zweite, ebenso wichtige Grenze: **Ein Reranker kann fehlenden Recall nicht heilen.** Was die
Erststufe gar nicht liefert, kann er nicht nach oben sortieren. Er ist die Antwort auf „die richtige
Fundstelle war unter den ersten 50, aber nicht unter den ersten 8" — die zweite Hälfte des
#938-Problems. Die erste Hälfte löst die lexikalische Suche.

## Wo das alles sitzt

```
Frage
  ↓
Teilfragen-Zerlegung (gebaut)
  ↓
je Teilfrage:  ┌── Vektorsuche  ──→ Liste V ┐
               └── Volltextsuche ──→ Liste T ┘   (neu, beide mit Rechtefilter)
  ↓
Reciprocal Rank Fusion über alle Listen aller Teilfragen (gebaut, eine Eingangsliste mehr)
  ↓
Cross-Encoder-Reranking über ~50 Kandidaten (neu, nur wenn die Rerank-Rolle aktiviert und belegt ist)
  ↓
Dokument-Vervollständigung, Auswahl auf top-k (gebaut)
  ↓
Antwortgenerierung mit Belegen (gebaut)
```

---

# Teil II — Technisches Zielbild

## Überblick

1. **Zuerst die Struktur, dann die Funktion.** Der Orchestrator wird auf benannte Pipeline-Stufen mit
   Schnittstelle umgestellt, bevor irgendein neuer Suchpfad einzieht.
2. **Jede Stufe kann ihr Ergebnis erklären.** Das ist keine Zutat für die Fehlersuche, sondern die
   Grundlage des Diagnosepfads in der Administration.
3. **Der lexikalische Pfad bleibt in PostgreSQL** — `tsvector` mit `german`-Konfiguration und GIN-Index
   auf der vorhandenen Chunk-Tabelle. Kein zweites System, kein zweiter Betriebsnachweis. Der
   **Bestand** bekommt seinen Volltextindex über einen eigenen, wiederaufnehmbaren Backfill mit
   sichtbarem Füllstand — nicht nebenbei durch die Migration.
4. **`ts_rank` ist kein BM25**, und das wird als bekannte Grenze geführt, nicht kaschiert. Für die
   rangbasierte Fusion genügt die Ordnung; ob sie genügt, entscheidet die Messung.
5. **Eskalation nur gegen gemessene Lücke.** Für den Fall, dass PostgreSQL-Volltext nicht trägt, sind
   zwei Stufen mit **Eintrittsbedingung** vorgesehen — keine davon wird vorsorglich gebaut.
6. **Die Fusion bleibt RRF**, mit einer Eingangsliste mehr je Teilfrage. Keine gewichteten Scores.
7. **Der Rechtefilter gilt im Volltextpfad identisch** und als Teil der Abfrage, nie als Nachfilter.
   Ein zweiter Suchpfad ist die häufigste Art, wie eine rechtebewusste Suche undicht wird.
8. **Reranking ist eine Modellrolle**, kein eingebautes Verfahren. Der Betreiber trägt einen Endpunkt
   ein wie für Chat und Einbettung und schaltet die Rolle ausdrücklich ein; ohne sie läuft die
   Hybrid-Suche ohne Reranking — sichtbar als Zustand, nicht als stille Auslassung.
9. **Aktiviert wird nur, was gemessen besser ist.** Die Lehre aus MMR — gebaut und per Messung
   voreingestellt abgeschaltet — gilt für jeden Baustein dieser Spezifikation.
10. **Konfiguration hat drei Ebenen mit klaren Zuständigkeiten.** Was niemand begründet umstellen kann,
    wird kein Parameter.
11. **Die Administration bekommt einen Diagnosepfad, keine Reglerwand.** Hauptzweck ist die Antwort auf
    „warum steht dieses Dokument nicht in der Antwort?".

---

## Arbeitspaket 1: Die Pipeline als benannte Stufen

> **Stand: gebaut** ([#1046](https://github.com/criew/opaa/issues/1046)). Der gebaute Ablauf steht in
> [Retrieval-Algorithmus (Ist-Stand)](./retrieval-algorithm.md#die-pipeline-als-benannte-stufen-1046);
> dieser Abschnitt bleibt die Begründung und der Zuschnitt.

### Warum das zuerst kommt

Der heutige Ablauf ist ein Orchestrator, der sieben Schritte nacheinander ausführt und jede Erweiterung
seit #912 als eigenen Zweig trägt (nachzulesen als
[Teil 1 des Ist-Stands](./retrieval-algorithm.md#teil-1-ablauf-einer-anfrage)). Fachlich ist er korrekt;
strukturell ist er an der Grenze. Volltextsuche und Reranking dort als weitere Sonderfälle einzuhängen,
hätte drei absehbare Folgen:

- **Die Erklärbarkeit stirbt zuerst.** Schon heute lässt sich „warum ist Dokument Z nicht dabei?" nur
  durch Lesen des Codes beantworten. Mit zwei weiteren Stufen ist auch das nicht mehr zumutbar — und
  ohne eine Antwort darauf ist der Diagnosepfad der Administration nicht baubar.
- **Die Messbarkeit stirbt als zweites.** Der Benchmark soll einzelne Stufen zu- und abschalten können,
  um ihren Beitrag zu isolieren. Über verzweigte Sonderfälle geht das nicht.
- **Die Reihenfolge wird implizit.** Ob Reranking vor oder nach der Dokument-Vervollständigung läuft,
  ist eine fachliche Entscheidung mit Folgen. Sie gehört an eine Stelle, an der man sie sieht.

### Der Zuschnitt

Die Pipeline ist eine **Folge benannter Stufen**, die alle dieselbe Gestalt haben: Sie nehmen eine
Kandidatenliste (samt Anfragekontext) entgegen und geben eine Kandidatenliste zurück. Eine Stufe kann
Kandidaten hinzufügen (Suchpfade), entfernen (Schwellen), umsortieren (Fusion, Reranking) oder ergänzen
(Dokument-Vervollständigung) — aber sie kann den Rechtekontext nicht verändern, und sie kann nicht mehr
Kandidaten sehen, als ihr übergeben wurden.

| Stufe | Aufgabe | Stand |
|---|---|---|
| Suchbereich bestimmen | lesbare Bibliotheken ∩ Kontext des Chats | gebaut |
| Teilfragen bilden | 1..`max-sub-queries` eigenständige Suchanfragen | gebaut (#923) |
| Vektorsuche je Teilfrage | `fetch-k` Kandidaten, Rechtefilter, Ähnlichkeitsschwelle | gebaut |
| **Volltextsuche je Teilfrage** | `fetch-k` Kandidaten, identischer Rechtefilter | gebaut (#1048), in der Fusion seit #1049 |
| Vielfaltsauswahl (MMR) je Teilfrage | Relevanz gegen Redundanz | gebaut, voreingestellt aus |
| Fusion | RRF über alle Listen aller Teilfragen | gebaut, erweitert (#1049) |
| **Reranking** | Cross-Encoder über die fusionierte Kandidatenmenge | **neu (AP 4)** |
| Dokument-Vervollständigung | bis `max-chunks-per-document` Chunks je Dokument | gebaut (#932/#935) |

Als Stufen registriert sind heute `SEARCH_SCOPE`, `SUB_QUERY_DECOMPOSITION`, `VECTOR_SEARCH`,
`FULL_TEXT_SEARCH`, `MMR_SELECTION`, `RANK_FUSION` und `DOCUMENT_COMPLETION`, in dieser Reihenfolge; die
Reranking-Stufe wird an der in der Tabelle genannten Stelle eingehängt.

Zwei Eigenschaften sind verbindlich, weil alles Weitere daran hängt:

**Jede Stufe ist einzeln abschaltbar** (`opaa.query.pipeline.disabled-stages`), und zwar so, dass die
abgeschaltete Stufe zur Identität wird —
die Pipeline läuft dann bit-identisch zu ihrem Zustand ohne diese Stufe. Nur so ist der Unterschied zweier
Läufe der Beitrag einer Stufe und nicht der Unterschied zweier Codepfade. **Damit der Benchmark diesen
Unterschied auch messen darf, fehlt noch ein Schritt:** Der Harness weist einen Lauf mit abgeschalteter
Stufe heute ab, weil kein Report-Feld festhält, welche Stufen liefen — ein solcher Lauf wäre von einem
vollständigen nicht unterscheidbar und würde als Codeänderung gegen die Baseline verbucht. Die
Stufen-Auswahl zur Messgröße zu machen ist ein Vertragsnachtrag mit neuem Fixpunkt, erhöhter
Vertragsversion und neu gezogenen Baselines. **Eine zweite Ausnahme, aus der Umsetzung:** die
Stufe, die den Rechtefilter setzt (`SEARCH_SCOPE`), ist nicht abschaltbar — „ohne diese Stufe" wäre keine
Messvariante, sondern eine Suche ohne Rechtefilter (ADR-0008 §5). Eine Konfiguration, die es versucht,
scheitert beim Start, nicht bei der Abfrage.

**Jede Stufe erklärt ihr Ergebnis, und zwar als Pflicht-Rückgabewert.** Das Erklärprotokoll ist Teil
des Rückgabewerts der Stufenschnittstelle — nicht ein optionaler Nebeneffekt, den eine Stufe erzeugen
kann oder auch nicht. Eine Stufe, die keines liefert, ist nicht implementiert; sie kann nicht
versehentlich stumm bleiben, weil die Schnittstelle sie sonst nicht erfüllt. Der Inhalt: welche
Kandidaten kamen herein, welche gingen hinaus, welche wurden verworfen, mit welcher Begründung und mit
welchem stufeninternen Wert. Ob das Protokoll **festgehalten** wird, entscheidet der Aufrufer — im
Regelbetrieb wird es verworfen, in der Diagnose ausgewertet; erzeugt wird es immer.

Daraus folgt ein **Abnahmekriterium von Arbeitspaket 1**: Ein Test prüft, dass die Zahl der
protokollierten Stufen der Zahl der registrierten Stufen entspricht. Genau dieser Test fängt den
absehbaren Fehler ab, dass eine später hinzugefügte Stufe in der Diagnose fehlt — und damit ein
Kandidat spurlos verschwindet, obwohl das Werkzeug vollständig aussieht.

Dieses Protokoll ist die Datengrundlage der
[Admin-Diagnose](#die-administrationsseite-suche--indexierung); es ist nicht zusätzlich zur Pipeline
gebaut, sondern von ihr erzeugt. Ein Diagnosewerkzeug, das die Pipeline nachbaut, würde irgendwann
etwas anderes anzeigen, als tatsächlich passiert ist.

### Was das Refactoring ausdrücklich nicht tut

Es ändert **kein Verhalten**. Nach Arbeitspaket 1 liefert die Pipeline für jede Frage dieselbe Auswahl
wie vorher — nachzuweisen über den Benchmark mit identischen Kennzahlen, nicht über Augenschein. Jede
gewollte Verhaltensänderung gehört in ein eigenes, einzeln messbares Arbeitspaket. Ein Refactoring, das
nebenbei die Suchqualität verändert, ist im Nachhinein nicht mehr von einer Regression zu unterscheiden.

---

## Arbeitspaket 2: Der lexikalische Suchpfad

> **Stand: gebaut** ([#1048](https://github.com/criew/opaa/issues/1048)); die Schemaänderung und der
> Backfill (AP 2a) davor mit [#1047](https://github.com/criew/opaa/issues/1047). Der gebaute Ablauf steht
> in [Retrieval-Algorithmus (Ist-Stand)](./retrieval-algorithm.md#3b-volltextsuche-je-teilfrage-1048);
> dieser Abschnitt bleibt die Begründung und der Zuschnitt.
>
> **Seit [#1049](https://github.com/criew/opaa/issues/1049) in der Fusion.** Die Stufe liefert je
> Teilfrage eine gelabelte Kandidatenliste, und diese Listen sind Eingangslisten der RRF (siehe
> [Arbeitspaket 3](#arbeitspaket-3-fusion)). Dort steht auch die Wirkung.
>
> **Die Auflage für #1049 ist erfüllt:** `opaa.query.full-text-search-enabled` ist Fixpunkt des
> Pipeline-Messvertrags (zusammen mit `fullTextBackfillComplete`, dem Zustand des Backfill-Tors für die
> gemessene Bibliothek), die Vertragsversion steht auf 3, und die Pipeline-Baselines aller drei Domänen
> sind neu gezogen. Der Harness-Guard
> (`PipelineHarnessSupport#requireMeasurableConfiguration`) weist einen Lauf mit `enabled=false` ab: Die
> committete Baseline beschreibt die ausgelieferte hybride Konfiguration, ein vector-only-Lauf gehört in
> den Variantenvergleich (`eval/variants/*-lexical-path.json`), der keine Baseline schreibt. Der
> Property-Name hat sich dabei von `opaa.query.full-text-search.enabled` zu
> `opaa.query.full-text-search-enabled` geändert — der Schalter ist mit der Aufnahme in die Fusion ein
> gemessener Query-Parameter geworden und liegt deshalb in `QueryProperties`, wo jeder gemessene
> Parameter liegt; die Umgebungsvariable `OPAA_QUERY_FULL_TEXT_SEARCH_ENABLED` ist unverändert.

### Entscheidung: Postgres-nativ

Die Volltextsuche läuft über `tsvector` mit der `german`-Konfiguration und einem GIN-Index auf der
vorhandenen Chunk-Tabelle. Damit bleibt es bei **einem** Datenspeicher: derselbe Sicherungslauf,
dieselbe Wiederherstellung, derselbe Verschlüsselungsnachweis, dieselbe Transaktion beim Indexieren. Das
ist der bewusste Unterschied zu Systemen wie RAGFlow oder Onyx, die eine eigene Suchengine mitbringen —
und er ist mit einem benannten Preis erkauft (nächster Abschnitt).

Die Konsequenz für die Indexierung **von Neuzugängen**: Der Volltextindex entsteht **beim Schreiben des
Chunks**, in derselben Transaktion wie Text und Vektor. Für jeden nach der Umstellung geschriebenen
Chunk gibt es damit keinen Zustand, in dem er vektorisiert, aber noch nicht volltextindiziert ist.

Für den **Bestand** gilt das ausdrücklich nicht. Die zum Umstellungszeitpunkt bereits gespeicherten
Chunks tragen keinen Volltextindex und bekommen ihn nur durch einen eigenen Nachlauf — das ist
Gegenstand des nächsten Abschnitts und nicht ein Nebeneffekt der Migration.

### Arbeitspaket 2a: Backfill des Bestands

Ein Volltextpfad, der nur die Hälfte des Bestands sieht, ist schlimmer als keiner: Er liefert Treffer,
und die fehlenden fallen niemandem auf. Der Backfill ist deshalb ein eigenes, benanntes Arbeitspaket
und Voraussetzung dafür, dass der Volltextpfad überhaupt in die Fusion aufgenommen wird.

**Die Schemaänderung legt nur Spalte und Index an.** Das Liquibase-Changeset erzeugt die
`tsvector`-Spalte und den GIN-Index — Letzteren mit `CREATE INDEX CONCURRENTLY` und damit außerhalb
einer Transaktion, weil ein Indexaufbau über die gesamte Chunk-Tabelle sonst den Schreibbetrieb für die
Dauer der Migration sperrt. Das Changeset befüllt **nichts**; ein `UPDATE` über den ganzen Bestand
innerhalb der Migration würde den Anwendungsstart um die Dauer des Backfills verzögern und bei Abbruch
in einem halb migrierten Zustand enden.

**Die Befüllung ist ein Batch, kein Migrationsschritt.** Verbindlich sind drei Eigenschaften:

- **Idempotent.** Ein zweiter Lauf über bereits befüllte Chunks ändert nichts und ist kein Fehler.
- **Wiederaufnehmbar mit persistiertem Fortschritt.** Der Lauf darf jederzeit abgebrochen werden — durch
  Neustart, Wartungsfenster oder Ausfall — und setzt danach dort fort, wo er stand, nicht am Anfang.
- **Rückwirkungsarm.** Er läuft in Stapeln mit begrenzter Größe und ist gegenüber dem Abfragebetrieb
  nachrangig; er gehört in dieselbe Kategorie wie ein Indizierungslauf (siehe
  [Skalierung und Zielwerte](./deployment-infrastructure.md#skalierung-und-zielwerte)).

**Der Füllstand je Bibliothek ist abfragbarer Zustand**, kein Logeintrag. Er ist die Datenquelle für
zwei Dinge zugleich: die Zustandsanzeige der [Administrationsseite](#was-die-seite-anzeigt) und den
Alarm „Volltextpfad inaktiv oder unvollständig". Eine Bibliothek, deren Chunks nur teilweise
volltextindiziert sind, ist damit ein sichtbarer Betriebszustand und nicht eine Vermutung, die aus
schlechten Antworten erschlossen werden muss.

### Die deutschen Besonderheiten

Deutsch ist für lexikalische Suche der unfreundlichere Fall, und zwar aus zwei Gründen.

**Komposita.** Ohne Zerlegung findet „Genehmigung" das „Baugenehmigungsverfahren" nicht — im
Verwaltungsbestand ist das kein Randfall, sondern die Regel. Die `german`-Konfiguration von PostgreSQL
bringt Stemming und Stoppwörter mit, aber keine Kompositazerlegung. Sie ist über ein
ispell-Wörterbuch nachrüstbar; ob sie **nötig** ist, entscheidet das Komposita-Teilsegment des
Benchmarks. Sie wird nicht vorsorglich aktiviert: Eine Zerlegung, die „Gebührenordnung" in „Gebühr" und
„Ordnung" auflöst, verwässert auch Treffer, und ob der Gewinn den Verlust übersteigt, ist eine
Messfrage.

**Exakte Kennungen.** Genau die Zeichenfolgen, für die der lexikalische Pfad gebaut wird —
Paragrafenverweise („§ 3 VGS", „§ 35 BauGB"), Aktenzeichen („4 K 1023/24.NW"), Erlassnummern —, werden
von jeder Analysekette am zuverlässigsten zerstört: Stemming schneidet Endungen ab, die Tokenisierung
zerlegt an Sonderzeichen, Kompositazerlegung zerteilt Kürzel. Sie werden deshalb **zusätzlich als
unzerlegte Tokens** geführt und bleiben von Stemming und Zerlegung unberührt. Sie sind Identifikatoren,
keine Wörter, und werden als solche behandelt: „§ 34" und „§ 35" müssen unterscheidbar bleiben, sonst
ist der ganze Pfad für seinen Hauptzweck wertlos.

Welche Muster als Kennung gelten, ist eine überschaubare, gepflegte Liste (Paragrafenverweise mit und
ohne Gesetzeskürzel, Aktenzeichen in den üblichen Formen, Erlass- und Drucksachennummern,
E-Mail-Adressen seit #1130) — nicht ein allgemeiner Erkennungsversuch. Eine falsch erkannte Kennung
erzeugt ein Token, das nie gesucht wird; eine nicht erkannte Kennung ist der Fehler, der wehtut.

Bei E-Mail-Adressen liegt die Lücke anders als bei Aktenzeichen: PostgreSQLs eigener Parser hält eine
Adresse bereits als ein einzelnes `email`-Token — die Zerstörung passiert nicht beim Schreiben, sondern
beim Fragen. `io.opaa.query.FullTextChunkSearch#wordTokens` zerlegt eine Frage an jedem
Nicht-Alphanumerikum, „max.mustermann@example.org" wird dort zu vier einzelnen Worttokens, die das eine
Chunk-Token nie treffen — belegt gegen ein echtes PostgreSQL (siehe `FullTextIdentifiersTest` und
`FullTextChunkSearchIntegrationTest`). Die Adresse als unzerlegtes Token auf beiden Seiten zu führen
behebt genau diese Asymmetrie, mit demselben Mechanismus wie bei Aktenzeichen.

**Wie es gebaut ist** (`io.opaa.indexing.FullTextIdentifiers`, #1048):

- **Dieselbe Liste auf beiden Seiten.** Schreibpfad und Suchpfad rufen dieselbe Methode. Ein Token aus
  einem Chunk und ein Token aus einer Frage entstehen im selben Code, sonst träfen sie einander nie.
- **Jedes schlüsselwortgeführte Muster hat ein schlüsselwortfreies Gegenstück.** Das ist keine
  Bequemlichkeit, sondern die Bedingung dafür, dass der Mechanismus überhaupt läuft: Ein Dokument
  schreibt „Dienstanweisung mit dem Aktenzeichen BAU-DA-2/2024", eine Frage nennt die Nummer nackt.
  Ein Muster, das das Schlüsselwort braucht, greift damit nur auf einer der beiden Seiten — und der
  Schutz tut still gar nichts. Genau das war bei acht von zehn `exact_identifier`-Golden-Fällen der
  Fall, bis das Review zu #1048 es aufdeckte; ein Symmetrietest hält beide Schreibweisen derselben
  Kennung jetzt gegeneinander.
- **Ein Kandidat wird nur als Kennung angenommen, wenn er strukturell eine ist** — mindestens eine
  Ziffer und mindestens ein Trennzeichen. Ohne diese Prüfung erzeugt „Aktenzeichen der Satzung" das
  Token `xakzder`, das dann auf jedem gewöhnlichen Fließtext-Chunk mit derselben Wendung mit Gewicht
  `A` sitzt: Rauschen an der Spitze der Rangliste, erzeugt vom Mechanismus, der sie schärfen soll.
- **Aufzählungen hinter `§§`** („§§ 34, 35 BauGB") liefern je Nummer ein Token; das Gesetzeskürzel gilt
  für alle, ein `Abs.` nur bei einer einzelnen Nummer — bei einer Aufzählung ist aus dem Text nicht
  entscheidbar, zu welcher es gehört.
- **Jedes Token ist kleingeschriebenes ASCII-Alphanumerisch mit Typpräfix** (`xpar`, `xakz`, `xnr`).
  Daraus folgen zwei tragende Eigenschaften: Ein solcher String kann nicht mit einem Lexem der deutschen
  Analysekette kollidieren, und er passiert `to_tsquery`, ohne ein Operatorzeichen mitzubringen.
- **Ein Paragrafenverweis liefert immer auch seine nackte Form.** `§ 35 BauGB` erzeugt `xpar35` *und*
  `xpar35baugb`; das spezifische Token trennt zwei Dokumente, die beide von § 35 sprechen, das nackte
  trifft noch, wenn nur eine Seite das Gesetz nennt.
- **Die Tokens tragen Gewicht `A`**, der Fließtext das voreingestellte `D`. Das ist der eigentliche
  Wirkmechanismus: `ts_rank` zählt Trefferhäufigkeit, also gewinnt sonst ein Chunk, der die Wörter der
  Frage oft wiederholt, gegen den einen Chunk, der die Kennung tatsächlich führt (gemessen als roter
  Testlauf in #1048). Technische Fußangel dabei: `array_to_tsvector` wäre der direktere Weg,
  unzerlegte Lexeme einzufügen, erzeugt aber einen Vektor **ohne Positionen** — und `setweight`
  schreibt Gewichte in Positionen, ist dort also stillschweigend wirkungslos. Gebaut ist deshalb
  `setweight(to_tsvector('simple', …), 'A')`.
- **Eine Änderung der Tokenbildung erhöht `content_tsv_version`.** Bestandszeilen gelten damit als
  fehlend und werden vom Backfill des Arbeitspakets 2a nachgezogen — ohne Migration, ohne Skript.

### Die bekannte Grenze: `ts_rank` ist kein BM25

PostgreSQLs `ts_rank` gewichtet nach Trefferhäufigkeit und Position, aber **nicht** nach der
Dokumentlängen-Normalisierung und der inversen Dokumenthäufigkeit, die BM25 ausmachen. Praktisch heißt
das: `ts_rank` überschätzt lange Chunks und unterscheidet zu wenig zwischen häufigen und seltenen
Begriffen — bei einem Bestand mit vielen ähnlich formulierten Satzungen ist genau das die schwache
Stelle.

Diese Grenze wird bewusst in Kauf genommen, und die Begründung ist die Fusionsmechanik:

- **RRF benutzt keine Scores, nur Ränge.** Der Volltextpfad muss nicht *richtig bewerten*, er muss die
  richtigen Kandidaten *weit oben* haben. Das ist eine deutlich schwächere Anforderung.
- **Der Volltextpfad ist im Hybrid zweitrangig.** Er trägt die Fälle, an denen die Vektorsuche
  strukturell scheitert (Kennungen, seltene Fachtermini) — dort steht der richtige Chunk meist mit
  deutlichem Abstand oben, weil er als einziger die Zeichenfolge überhaupt enthält. Die Feinsortierung
  im Mittelfeld übernimmt ohnehin der Reranker.

Trotzdem ist es eine Grenze und keine Nebensache: Sie steht in der Betriebsdokumentation, sie ist die
Eintrittsbedingung der Eskalationsstufen unten, und sie ist der erste Verdacht, wenn das
Benchmark-Segment „exakte Kennungen" hinter der Erwartung bleibt.

### Eskalationsstufen mit Eintrittsbedingung

Keine dieser Stufen wird vorsorglich gebaut. Jede hat eine **gemessene** Eintrittsbedingung; „es wäre
sauberer" ist keine.

| Stufe | Was | Eintrittsbedingung | Preis |
|---|---|---|---|
| **0 (gewählt)** | `tsvector`/`german` + GIN, Kennungsschutz | — | `ts_rank` statt BM25 |
| **1** | echte BM25-Extension im **selben** PostgreSQL: pgroonga (PostgreSQL-Lizenz, N-Gramm-Ansatz, robust gegen Komposita) oder pg_search/ParadeDB (AGPL, echtes BM25) | Prüfbare Aussage über benannte Fallgruppen des Benchmarks — siehe unten | Eigenes PostgreSQL-Image statt des Standard-Images; bei ParadeDB zusätzlich die AGPL-Frage für den Betreiber |
| **2 (letzte Stufe)** | externe Suchengine (OpenSearch) neben PostgreSQL | Stufe 1 ist gemessen unzureichend **oder** die Lastanforderung ist mit PostgreSQL nachweislich nicht erfüllbar | Zweiter Betriebsnachweis, zweite Wiederherstellung — und vor allem: der Rechtefilter existiert dann **zweimal** |

**Die Eintrittsbedingung der Stufe 1 ist eine prüfbare Aussage, keine Einschätzung.** Sie hat die
Gestalt:

> Die Fallgruppen `compound_word` und `exact_identifier` bleiben — mit aktivem Kennungsschutz und
> aktivem Reranker — unter Hit Rate@5 = X, obwohl der Rohvektor-Pfad die betreffenden Kandidaten im
> gemessenen Fenster zeigt.

Der Nachsatz trägt die eigentliche Aussage: Liegt der Kandidat gar nicht im Fenster, ist die Ursache
kein Rangproblem des lexikalischen Pfads, und eine BM25-Extension hilft nicht. Der Schwellenwert X
darf zum Zeitpunkt dieser Spezifikation offen bleiben, **muss aber vor dem ersten Variantenvergleich
festgelegt und committet sein** — eine nach der Messung gewählte Schwelle belegt nichts (Verfahren
siehe [Retrieval-Benchmark](./retrieval-benchmark.md#6-der-benchmark-als-eintrittsbedingungs-maschine)).

#### Festlegung: **X = 0,80** (committet mit #1048, vor dem ersten Variantenvergleich)

**Die Schwelle gilt je Fallgruppe einzeln, nicht für beide gemeinsam.** Bleibt *eine* der beiden Gruppen
unter X, ist die Eintrittsbedingung für den Mechanismus dieser Gruppe erfüllt — `compound_word` spricht
für Kompositabehandlung, `exact_identifier` für eine echte BM25-Bewertung. Ein Mittelwert über beide
würde genau die Aussage verwischen, für die die Gruppen getrennt ausgewiesen werden (Koordinator-
Entscheidung im Review zu #1048).

Die Zahl ist aus dem Betrieb begründet, nicht aus den Messwerten abgeleitet: Hit Rate@5 = 0,80 heißt
„höchstens jede fünfte Frage dieser Klasse verfehlt die richtige Fundstelle im sichtbaren Fenster".
Unterhalb davon ist eine Klasse, für die der lexikalische Pfad überhaupt gebaut wurde, nicht
betriebstauglich; oberhalb ist der verbleibende Rest kein Argument für einen zweiten
Suchmechanismus mit eigenem PostgreSQL-Image.

Zur Einordnung, ausdrücklich **nicht** als Herleitung — die Ausgangswerte der Verwaltungsdomäne auf dem
Pipeline-Pfad (Baseline gezogen mit #1043, also vor jeder Zeile dieses Arbeitspakets):
`compound_word` 0,778, `exact_identifier` 1,000. Die Schwelle liegt damit vor der Messung
fest und **kann** ablehnen: Sie würde bei unverändertem `compound_word`-Wert greifen, bei unverändertem
`exact_identifier`-Wert nicht. Genau das ist der Zweck einer Eintrittsbedingung — sie muss beide Ausgänge
haben, sonst dokumentiert sie eine Entscheidung, statt sie herbeizuführen.

Von den zwei Bedingungen der Aussage ist eine seit [#1049](https://github.com/criew/opaa/issues/1049)
erfüllt — der lexikalische Pfad wirkt auf die Endauswahl —, die zweite nicht: Einen Reranker gibt es
erst mit Arbeitspaket 4. Bis dahin bleibt die Bedingung **nicht auswertbar**, was sie nicht schwächer
macht, sondern der Grund ist, sie festgeschrieben zu haben.

Der Zwischenstand nach #1049, ausdrücklich **keine** Auswertung der Eintrittsbedingung: `compound_word`
liegt bei Hit Rate@5 0,778 (unverändert gegenüber dem Ausgangswert — die Fusion hebt in dieser Klasse
Ränge und Recall, aber keinen zusätzlichen Fall in die ersten fünf), `exact_identifier` bei 1,000.
Bliebe es dabei, spräche das für Kompositabehandlung und gegen eine BM25-Extension — beides zu
entscheiden erst nach Arbeitspaket 4.

Ist die Bedingung erfüllt, gilt Stufe 1 dennoch nicht automatisch als beauftragt. Vier
**Betriebsauflagen** sind vor dem Bau zu erfüllen; jede einzelne verworfene Auflage verwirft die Stufe:

- **Der Extension-Index ist jederzeit aus dem Bestand rekonstruierbar und nie alleinige Datenquelle.**
  Was nur im Extension-Index steht, ist nach einer Wiederherstellung verloren; die Chunk-Tabelle bleibt
  die Wahrheit.
- **Der Rückfall auf Stufe 0 gehört zur Abnahme.** Ein Wiederherstellungstest auf dem
  PostgreSQL-Standard-Image — ohne Extension, mit `tsvector`/GIN — ist Teil der Abnahme, nicht eine
  spätere Übung. Andernfalls ist das eigene Image eine Einbahnstraße.
- **Die Pflege des Images hat eine benannte Verantwortung.** Ein eigenes PostgreSQL-Image bedeutet
  eigenen CVE-Nachzug bei jedem PostgreSQL- und Extension-Update. Ohne benannte Zuständigkeit dafür
  wird die Stufe nicht gebaut.
- **Die Lizenzfrage ist vor dem Bau entschieden.** ParadeDB/pg_search steht unter AGPL; ob diese
  Lizenz in der Auslieferungsform dieses Projekts tragbar ist, wird vorab entschieden und nicht
  während der Umsetzung.

Der Grund, warum Stufe 2 die letzte ist, hat nichts mit Aufwand zu tun. Eine externe Engine bedeutet
einen zweiten Ort, an dem die Bibliothekszuordnung gepflegt wird, und einen Synchronisationsweg
dazwischen. **Jeder Synchronisationsfehler ist damit ein potenzielles Berechtigungsleck** — ein Chunk,
dessen Bibliothekszuordnung im externen Index veraltet ist, wird für Konten gefunden, die ihn nicht
lesen dürfen. Bei einem einzigen Speicher ist dieser Fehlerfall konstruktiv ausgeschlossen: Es gibt
keinen zweiten Stand, der abweichen könnte. Das ist der eigentliche Preis, und er wird nur gegen einen
gemessenen Nachweis gezahlt.

**Zurückgestellt: `pg_trgm`.** Trigramm-Ähnlichkeit fängt Tippfehler und Teilwortsuchen ab. Beides ist
im heutigen Betrieb kein belegtes Problem — Tippfehler normalisiert bereits die
LLM-Teilfragen-Zerlegung nebenbei (#923). Wiedervorlage bei gemessenem Bedarf, nicht vorher.

### Rechtefilter im Volltextpfad

Der Filter auf die lesbaren Bibliotheken (`library_id IN (...)`) ist **Teil der Volltextabfrage selbst**,
genau wie im Vektorpfad — nie ein Filter auf deren Ergebnis. Das ist keine Optimierung, sondern die
tragende Zusicherung von ADR-0008 (der Grundsatz ist mit #326 in die Spezifikation überführt worden;
maßgeblich ist heute
[Durchsetzung zur Abfragezeit](./spaces-and-assets.md#durchsetzung-zur-abfragezeit)).

Zwei Folgerungen sind verbindlich:

- **Kein Suchpfad ohne Rechtefilter, auch nicht temporär und auch nicht für die Diagnose.** Der
  Diagnosepfad der Administration führt seine Abfrage im gewählten Berechtigungskontext aus — er umgeht
  den Filter nicht, er setzt ihn anders (siehe [Berechtigungs-Leitplanken](#berechtigungs-leitplanken)).
- **Der Filter wird in einem Test abgesichert, der ihn tatsächlich ausführt.** Ein Test, der den
  Volltextpfad mockt, prüft den Filter nicht — das ist genau die Kategorie „mockt weg, worum es geht"
  aus den [Agenten-Anweisungen](../../AGENTS.md#reproduktionsnachweis).

---

## Arbeitspaket 3: Fusion

> **Stand: gebaut** ([#1049](https://github.com/criew/opaa/issues/1049)). Der gebaute Ablauf steht in
> [Retrieval-Algorithmus (Ist-Stand)](./retrieval-algorithm.md#5-reciprocal-rank-fusion); dieser
> Abschnitt bleibt die Begründung und der Zuschnitt.
>
> **Gemessene Wirkung** (Verwaltungsdomäne, Pipeline-Messpfad, CPU-Testcontainer-Lauf vom 2026-09-01,
> `vector-only` gegen `vector+fulltext-rrf` im selben Lauf über denselben Index):
>
> | Gruppe | Hit Rate@5 | MRR@8 | nDCG@8 | Recall@8 |
> |---|---|---|---|---|
> | gesamt | 0,783 → **0,935** | 0,576 → **0,758** | 0,558 → **0,749** | 0,696 → **0,880** |
> | `literal_term_weak_embedding` (die #938-Klasse) | 0,556 → **0,889** | 0,226 → **0,593** | 0,324 → **0,633** | 0,611 → **0,833** |
> | `compound_word` | 0,778 → 0,778 | 0,657 → **0,796** | 0,431 → **0,675** | 0,444 → **0,722** |
> | `exact_identifier` | 1,000 → 1,000 | 0,900 → 0,900 | 0,918 → **0,926** | 1,000 → 1,000 |
> | `metadata_filter` | 0,667 → **1,000** | 0,537 → **0,685** | 0,595 → **0,766** | 0,778 → **1,000** |
> | `multi_hop` | 0,889 → **1,000** | 0,522 → **0,800** | 0,480 → **0,726** | 0,611 → **0,833** |
>
> Die Werte sind die der committeten Baseline (`eval/baseline/pipeline-verwaltung.json`) und die der
> `vector-only`-Variante desselben Laufs; keine Gruppe verschlechtert sich.
>
> Auf Einzelfallebene löst der Pipeline-Pfad seither **zwölf** Fälle zusätzlich und **einen** nicht
> mehr (`verw-meta-003`, dessen ersten Rang jetzt ein lexikalischer Treffer belegt). Genau einer der
> zwölf — `verw-comp-006` — wird auch vom Rohvektor-Pfad gelöst und wechselt deshalb auf `solved`;
> die übrigen elf bleiben `known_gap` und tragen ihre Pfad-Asymmetrie als
> `expected_state_exception` committet (siehe
> [Retrieval-Benchmark §5](./retrieval-benchmark.md) und
> `eval/corpus/verwaltung/MAINTENANCE.md`).

Hier ist am wenigsten zu tun, und das ist beabsichtigt. Die vorhandene Reciprocal Rank Fusion fusioniert
bereits die Ranglisten mehrerer Teilfragen. Der Volltextpfad wird **eine weitere Eingangsliste je
Teilfrage**:

```
Teilfrage 1  →  Vektorliste V1, Volltextliste T1
Teilfrage 2  →  Vektorliste V2, Volltextliste T2
Teilfrage 3  →  Vektorliste V3, Volltextliste T3
                        ↓
              RRF über alle sechs Listen
```

Drei Festlegungen dazu:

**RRF, keine gewichteten Scores.** Die Literatur zeigt, dass eine gewichtete Score-Fusion RRF schlagen
kann — aber erst, wenn das Gewicht auf dem eigenen Bestand abgestimmt wurde (Bruch et al., TOIS 2023).
Ein ungetuntes Gewicht ist schlechter als gar keines, weil es die Suche unbemerkt auf eine Modalität
kippt. RRF ist tuningfrei und robust; eine Gewichtung wird erst erwogen, wenn der Benchmark ein Tuning
tragen kann. Bis dahin ist die Dämpfungskonstante 60 ein interner Wert der
[Ebene 1](#konfigurations-ebenenmodell), kein Regler.

**Dedupliziert wird per Chunk-Kennung, nie per Score.** Unverändert gegenüber heute, aus demselben Grund
wie in #912: Werte verschiedener Verfahren sind nicht vergleichbar. Ein Chunk, den Vektor- und
Volltextpfad unabhängig voneinander finden, ist **ein** Kandidat mit zwei Beiträgen — nicht zwei
Kandidaten.

**Der Volltextpfad hat dieselbe Ausfallsicherheit wie die Teilfragen-Zerlegung.** Fällt er aus, läuft
die Fusion mit den verbleibenden Listen weiter. Eine defekte oder fehlende Volltextspalte darf zu
schlechterer Suchqualität führen, nie zu einem Fehler für den fragenden Menschen.

---

## Arbeitspaket 4: Reranking als Modellrolle

> **Stand: gebaut, per Voreinstellung aus** ([#1050](https://github.com/criew/opaa/issues/1050)).
> Der gebaute Ablauf steht in
> [Retrieval-Algorithmus (Ist-Stand), Schritt 5b](./retrieval-algorithm.md#5b-reranking-1050); dieser
> Abschnitt bleibt die Begründung und der Zuschnitt. Was #1050 geliefert hat:
>
> - **Die Rerank-Rolle** (`io.opaa.llm.RerankModelRole`, `RerankProperties`, `RerankClient`) auf
>   derselben Ebene wie Chat und Einbettung, angebunden über `POST {Basis-Adresse}/rerank`.
> - **Der explizite Schalter** `OPAA_RERANK_ENABLED`, getrennt von `OPAA_RERANK_BASE_URL`,
>   `OPAA_RERANK_MODEL` und `OPAA_RERANK_API_KEY`. Voreinstellung: aus.
> - **Der Widerspruchszustand**: Startmeldung auf Fehler-Ebene *und* der fortlaufend abfragbare
>   Zustand hinter `RerankRoleStatusProvider#currentStatus()` (vier Zustände: `DISABLED`,
>   `READY`, `UNCONFIGURED`, `UNREACHABLE`). Die Administrationsseite aus
>   [#1053](https://github.com/criew/opaa/issues/1053) liest genau diesen Vertrag; #1050
>   liefert die Implementierung, nicht die Oberfläche. Weder die Zustandsabfrage noch die
>   Prüfung auf dem Anfragepfad wartet dafür auf einen Netzaufruf: Der Erreichbarkeitszustand
>   wird beim Start, im Minutentakt und bei jedem echten Rerank-Aufruf fortgeschrieben.
> - **Die Stufe** `RERANK` zwischen Fusion und Dokument-Vervollständigung, mit eigenem Eintrag im
>   Erklärprotokoll — inklusive des Status `UNAVAILABLE` für „eingeschaltet, aber nicht nutzbar".
> - **Die Kandidatenzahl** `OPAA_QUERY_RERANK_CANDIDATE_COUNT` (Startwert 50, `0` schaltet die Stufe
>   über ihren eigenen Parameter ab), gemessen über den Variantenvergleich
>   `eval/variants/verwaltung-reranking.json` — siehe
>   [Gemessene Wirkung](#gemessene-wirkung-und-gemessene-kandidatenzahl-1050).
>
> **Nicht geliefert**: die Aktivierung. Siehe [Die Lehre aus MMR](#die-lehre-aus-mmr).

### Die Entscheidung

Reranking wird **kein eingebautes Verfahren, sondern ein konfigurierbarer Aufgabentyp** im
Modell-Schichtenmodell — auf derselben Ebene wie Chat und Einbettung. Der Betreiber trägt einen
Rerank-Endpunkt ein: Ollama, vLLM, ein anderer OpenAI-kompatibler Dienst oder ein Cloud-Anbieter. Das
Zielbild sieht diese Rolle bereits vor (siehe
[Voreinstellungen je Aufgabe](./llm-integration.md#voreinstellungen-und-parameter-je-aufgabe), Zeile
„Reranking"); diese Spezifikation ist der Anlass, sie zu bauen.

Daraus folgt unmittelbar:

> **Ohne konfiguriertes Rerank-Modell läuft die Hybrid-Suche ohne Reranking.** Sie ist dann immer noch
> besser als der heutige Stand — der lexikalische Pfad wirkt unabhängig davon.

Das ist keine Notlösung, sondern der erwartete Normalfall für einen Teil der Installationen. Eine
Behörde ohne GPU bekommt Hybrid-Suche; eine Behörde mit GPU bekommt Hybrid-Suche und Reranking. Es gibt
keine Fähigkeit, die zwingend einen Aufruf nach außen verlangt — dieselbe Zusage wie für alle anderen
Modellrollen.

### „Aus" muss eine Aussage sein, kein Zustand

Ein Reranking, das ausbleibt, weil eine Umgebungsvariable falsch geschrieben wurde, sieht im Betrieb
genauso aus wie ein Reranking, das bewusst nicht gewollt ist: Die Suche antwortet, nur etwas
schlechter. Diese Verwechselbarkeit wird konstruktiv ausgeschlossen.

- **`OPAA_RERANK_ENABLED` ist ein expliziter Schalter.** Reranking aus heißt: Die Betreiberin hat es
  ausgeschaltet — nicht: eine Konfigurationszeile fehlt. Die Endpunktangaben bleiben davon getrennt;
  der Schalter drückt die Absicht aus, die Endpunktangaben das Wie.
- **Ein Widerspruch wird beim Start gemeldet und nicht stillschweigend aufgelöst.** Ist der Schalter
  gesetzt, die Rerank-Rolle aber unbelegt oder ihr Endpunkt nicht erreichbar, meldet das die Anwendung
  beim Start deutlich und führt den Zustand fortlaufend als Statusmeldung
  ([Administrationsseite](#was-die-seite-anzeigt)). Die Suche läuft in diesem Fall weiter — ohne
  Reranking, aber nicht unbemerkt.
- **Der Startlog ist nicht der einzige Ort.** Eine Meldung, die nur beim Start erscheint, ist einen Tag
  später niemandem mehr zugänglich; deshalb der abfragbare Zustand daneben.
- **Auch das Erklärprotokoll unterscheidet die beiden Fälle.** Der Rollenzustand reist als
  `RerankAvailability` (abgeschaltet / an-aber-nicht-nutzbar / nutzbar) im `RetrievalContext` mit,
  nicht als Ja/Nein. Die Rerank-Stufe meldet `DISABLED` nur für eine Betreiberentscheidung
  (`OPAA_RERANK_ENABLED=false` oder `rerank-candidate-count=0`) und `UNAVAILABLE` für die Störung —
  sonst stünde genau die Verwechselbarkeit, die dieser Abschnitt ausschließt, im Diagnosewerkzeug.
  Ein leerer Kandidatensatz bekommt eine eigene Notiz, statt fälschlich die Rolle zu beschuldigen.
- **Das Diagnosewerkzeug liest denselben Rollenzustand wie der Chatpfad.** Es erklärt sonst eine
  Suche, die niemand gestellt hat — und zwar genau dann, wenn jemand fragt „warum diese
  Fundstellen?". Ebenso nimmt die Pipeline das verbreiterte Kandidatenfenster zurück, wenn die
  Rerank-Stufe über `opaa.query.pipeline.disabled-stages` abgeschaltet ist: Ohne die Stufe stellt
  niemand die `top-k`-Deckelung wieder her.

### Hardware ist eine Deployment-Entscheidung

Die Frage „GPU in der Behörden-Installation, ja oder nein?" wird von dieser Spezifikation **nicht**
beantwortet, weil sie nicht produktseitig beantwortbar ist. Was das Produkt liefert, ist die Grundlage
für die Entscheidung: die gemessene Qualität aus dem Benchmark und das gemessene Latenzprofil aus dem
Arbeitspaket „Latenz-/Hardwareprofil" (nächster Abschnitt).

Die folgende Übersicht ist **keine Messung**, sondern eine Sammlung von Erfahrungswerten und
Literaturangaben zur Vorauswahl der Kandidaten — sie sagt, welche Modelle überhaupt geprüft werden,
nicht welches gewinnt:

| Modell | Betrieb | Erwartung aus der Literatur (unverbindlich) |
|---|---|---|
| `bge-reranker-v2-m3` (Apache 2.0) | CPU-tauglich, klein | Der Kandidat für Installationen ohne GPU; multilingualer De-facto-Standard |
| `Qwen3-Reranker-4B` (Apache 2.0) | GPU | Deutlich stärker in der Literatur, deutlich teurer im Betrieb |
| Cloud-Reranker (Cohere, Voyage) | API | Nur, wo die Behörde Aufrufe nach außen ausdrücklich erlaubt hat |

Eine Modellwahl per Leaderboard ist bei deutschsprachigen Verwaltungstexten keine Grundlage; die
Entscheidung fällt gegen die Verwaltungs-Evaldomäne und gegen das gemessene Latenzprofil.

### Arbeitspaket „Latenz-/Hardwareprofil"

> **Stand: zurückgestellt** (Maintainer-Entscheidung, 02.09.2026). Das Profil wurde **nicht** erhoben;
> [#1051](https://github.com/criew/opaa/issues/1051) ist ungebaut geschlossen, weil die Frage der
> Reranking-Aktivierung zunächst grundsätzlich überdacht wird. Der Abschnitt bleibt als Zuschnitt
> stehen — die Anforderungen unten gelten unverändert, sobald die Frage wieder aufgenommen wird.
>
> **Folge:** Es gibt keine Aktivierungsempfehlung, und es wird keine ausgesprochen. Reranking bleibt
> voreingestellt **aus** (`OPAA_RERANK_ENABLED` steht auf `false`); ohne ausdrückliches Einschalten
> durch einen Betreiber ändert sich am Verhalten der Suche nichts.
>
> **Was aus [#1050](https://github.com/criew/opaa/issues/1050) bereits bekannt ist:** Die Qualität
> trägt deutlich (Verwaltungsdomäne, nDCG@8 gesamt 0,726 → 0,867; `literal_term_weak_embedding`
> 0,494 → 0,846; `compound_word` 0,731 → 0,941). Das Problem ist die Laufzeit — rund **drei Minuten
> je Frage** bei 50 Kandidaten auf 20 CPU-Kernen mit `BAAI/bge-reranker-v2-m3`. Eine Installation
> ohne GPU rerankt mit diesem Modell voraussichtlich nicht sinnvoll; die naheliegende
> Referenzhardware (Demo-Instanz) hat keine nutzbare GPU.
>
> **Zwei Fallen für eine spätere Messung:**
> [#1154](https://github.com/criew/opaa/issues/1154) — das Rerank-Zeitlimit ist für CPU-Betrieb zu
> knapp, ein langsamer, aber funktionierender Endpunkt wird als `UNREACHABLE` gemeldet; wer ohne
> Anhebung misst, misst einen Ausfall statt einer Latenz.
> [#1153](https://github.com/criew/opaa/issues/1153) — die Kandidatenzahl 50 ist nicht belegt, die
> Wahl zwischen 25 und 50 hängt an einem einzelnen `multi_hop`-Fall.

Qualität und Latenz werden getrennt gemessen, weil sie verschiedene Messaufbauten brauchen. Der
[Retrieval-Benchmark](./retrieval-benchmark.md) misst Qualität in einem Testcontainers-Lauf auf
wechselnder CI-Hardware — für Laufzeitaussagen ist er konstruktionsbedingt untauglich, und er erhebt
diesen Anspruch ausdrücklich nicht (siehe dort
[Abgrenzung](./retrieval-benchmark.md#abgrenzung)).

Das Latenzprofil ist deshalb ein **eigenes benanntes Arbeitspaket** mit eigenem Aufbau:

- **Definierte Referenzhardware.** Gemessen wird auf einer benannten, wiederholbar beschriebenen
  Maschine — naheliegend die Demo-Instanz — und nicht auf dem jeweiligen Entwicklungsrechner. Die
  Hardwarebeschreibung gehört zu jeder veröffentlichten Zahl.
- **Ausdrücklich außerhalb der Testcontainers-CI.** Ein Laufzeitwert aus einem GitHub-Actions-Runner
  wäre ein Zufallswert und würde als Zusage gelesen.
- **Gemessene Größen:** zusätzliche Latenz je Rerank-Aufruf, aufgeschlüsselt nach Modell,
  Kandidatenzahl und Chunk-Länge, samt Verhalten unter gleichzeitigen Anfragen.
- **Ergebnis ist eine Empfehlung, keine Zusicherung:** je Hardwareklasse eine Aussage, ob und mit
  welchem Modell Reranking sinnvoll aktivierbar ist.

**Das Latenzprofil ist Voraussetzung der Reranking-Aktivierungsempfehlung.** Ohne es wird kein
Vorschlag ausgesprochen, Reranking voreingestellt zu aktivieren — ein Qualitätsgewinn, dessen Preis
niemand kennt, ist keine Entscheidungsgrundlage.

### Zuschnitt im Ablauf

Das Reranking läuft **nach der Fusion und vor der Dokument-Vervollständigung**. Die Reihenfolge ist
begründet: Die Dokument-Vervollständigung ergänzt gezielt Geschwister-Chunks bereits ausgewählter
Dokumente (#932) — sie soll auf der final gerankten Auswahl arbeiten, nicht auf einer Vorstufe, die der
Reranker gleich wieder umsortiert.

Die Kandidatenmenge liegt bei rund **50** Chunks. Der Wert folgt der Diagnose aus #938: Die dort
verfehlte Fundstelle lag auf Rang 50, also genau an der Reichweitengrenze eines Rerankers mit dieser
Kandidatenzahl. Er ist ein Startwert der [Ebene 1](#konfigurations-ebenenmodell) und wird gemessen, nicht
gesetzt und vergessen.

### Latenz ist eine zu messende Größe, kein Versprechen

Das Zielbild nennt für das Reranking „unter 200 ms zusätzlich" (siehe
[Abfragelatenz](./data-indexing-rag.md#abfragelatenz)). Dieser Wert bleibt als **Zielwert für die
Auslegung** stehen und wird hier ausdrücklich **nicht** zu einer Zusicherung erhoben. Was tatsächlich
gilt, hängt an Modell, Hardware, Kandidatenzahl und Chunk-Länge — vier Größen, von denen das Produkt
zwei nicht kennt. Die Zahlen liefert das
[Arbeitspaket „Latenz-/Hardwareprofil"](#arbeitspaket-latenz-hardwareprofil); die Installation
entscheidet damit, welchen Punkt auf der Qualität-Latenz-Kurve sie wählt.

### Gemessene Wirkung und gemessene Kandidatenzahl (#1050)

Messaufbau: Verwaltungs-Evaldomäne, 46 Golden-Fälle, Pipeline-Messpfad, ein Index, ein Lauf,
Variantenvergleich `eval/variants/verwaltung-reranking.json`. Referenzvariante ist die ausgelieferte
Konfiguration (Kandidatenfenster 0, kein Reranking); die drei Varianten laufen gepaart über
denselben Index, sind also untereinander vergleichbar. Rerank-Modell: `BAAI/bge-reranker-v2-m3`
(Apache 2.0) über Text Embeddings Inference auf CPU — der Kandidat, den die Übersicht oben für
Installationen ohne GPU nennt. Die Einbettungen dieses Laufs stammen aus einem externen
Ollama-Endpunkt; die absoluten Zahlen sind deshalb **nicht** mit der committeten Baseline
vergleichbar, die Deltas innerhalb des Laufs sehr wohl. Die committeten Baselines bleiben
unverändert: Die ausgelieferte Konfiguration rerankt nicht, und der Harness lehnt einen
Baseline-Lauf ab, der es täte.

| Klasse | n | nDCG@8 ohne → 25 → 50 | MRR@8 ohne → 25 → 50 | Recall@8 ohne → 25 → 50 |
|---|---|---|---|---|
| gesamt | 46 | 0,726 → 0,868 → **0,867** | 0,733 → 0,906 → **0,886** | 0,859 → 0,909 → **0,920** |
| `compound_word` | 9 | 0,731 → 0,941 → **0,941** | 0,861 → 1,000 → **1,000** | 0,722 → 0,926 → **0,926** |
| `literal_term_weak_embedding` | 9 | 0,494 → 0,846 → **0,846** | 0,444 → 0,889 → **0,889** | 0,722 → 0,833 → **0,833** |
| `exact_identifier` | 10 | 0,889 → 1,000 → **1,000** | 0,870 → 1,000 → **1,000** | 1,000 → 1,000 → **1,000** |
| `metadata_filter` | 9 | 0,807 → 0,862 → **0,807** | 0,741 → 0,815 → **0,741** | 1,000 → 1,000 → **1,000** |
| `multi_hop` | 9 | 0,691 → 0,677 → **0,725** | 0,731 → 0,815 → **0,787** | 0,833 → 0,778 → **0,833** |

Zwei Aussagen fallen daraus, und nur diese beiden:

- **Die Wirkung ist groß und trifft genau die schwachen Klassen.** `literal_term_weak_embedding` —
  die #938-Klasse — steigt im nDCG@8 von 0,494 auf 0,846, `compound_word` von 0,731 auf 0,941. Das
  sind die beiden Klassen, die die Neubewertung vor dem Bau als unter dem Schwellenwert liegend
  ausgewiesen hat.
- **Der Startwert 50 trägt, und er trägt aus einem benennbaren Grund.** Bei Fenster 25 verliert
  `multi_hop` gegenüber der Referenz (Recall@8 0,833 → 0,778, nDCG@8 0,691 → 0,677, vollständig
  getroffene Erwartungsmengen 0,667 → 0,556): Mehrteilige Fragen brauchen zwei verschiedene
  Dokumente, und das zweite steht oft hinter Rang 25. Bei Fenster 50 verschwindet diese Regression
  vollständig — **keine Klasse liegt dort unter der Referenz**. 25 ist billiger und in vier von fünf
  Klassen gleichwertig; 50 ist die Zahl, die ohne Regression an anderer Stelle auskommt, und bleibt
  deshalb der Default.

**Was diese Messung nicht sagt.** Sie sagt nichts über Latenz: Der Lauf brauchte auf einer
20-Kern-CPU rund 2,3 Stunden je Rerank-Variante, also grob drei Minuten je Frage bei 50 Kandidaten.
Das ist eine Zahl über den Messaufbau, keine über den Betrieb — der Aufbau ist für Qualität gebaut,
nicht für Laufzeit (siehe [Retrieval-Benchmark, Abgrenzung](./retrieval-benchmark.md#abgrenzung)).
Eine belastbare Laufzeitaussage liefert erst das
[Arbeitspaket „Latenz-/Hardwareprofil"](#arbeitspaket-latenz-hardwareprofil), und **ohne dieses
Arbeitspaket wird keine Aktivierungsempfehlung ausgesprochen** — die Größenordnung oben legt
allerdings nahe, dass eine Installation ohne GPU mit diesem Modell nicht sinnvoll rerankt.

**Einschränkung dieses Laufs, offen benannt:** Der Rerank-Endpunkt wurde in dem Moment gestoppt, in
dem die letzte Anfrage der Variante `rerank-50` zurückkam. Sollte die allerletzte Anfrage davon
betroffen gewesen sein, wäre einer der 46 Fälle dieser Variante ohne Neubewertung gemessen worden.

Die **Qualitätsaussage** hängt an keinem Einzelfall — die Sprünge bei `literal_term_weak_embedding`
und `compound_word` sind dafür zu groß. Die **Wahl der Kandidatenzahl 50 gegenüber 25** hängt sehr
wohl an einem: `multi_hop` hat neun Fälle, ein Fall entspricht 0,111 im Klassenwert, und genau ein
Fall ist der gesamte Unterschied zwischen den beiden Fenstern. Die Zahl 50 ist damit begründet, aber
nicht belegt; die Wiederholung mit dem inzwischen gebauten Ausfallwächter ist als
[#1153](https://github.com/criew/opaa/issues/1153) festgehalten.

### Die Lehre aus MMR

Es gibt in diesem Projekt bereits einen gebauten, fachlich gut begründeten Retrieval-Baustein, der per
Voreinstellung **abgeschaltet** ist: die Vielfaltsauswahl MMR (`mmr-lambda: 1,0`, siehe
[Ist-Stand, Schritt 4](./retrieval-algorithm.md#4-mmr-auswahl-je-teilfrage)). Die Literatur sprach dafür,
die Messung auf dem eigenen Korpus sprach dagegen: 19 von 20 Mehrthemen-Fällen mit Vielfaltsauswahl
gegen 20 von 20 ohne. Der Baustein blieb, die Voreinstellung wurde nach der Messung gesetzt.

**Für Reranking gilt dasselbe Prinzip, und es ist bindend:**

- Der Baustein wird gebaut, weil die Evidenzlage außerhalb dieses Projekts stark ist.
- **Er wird per Voreinstellung erst aktiv, wenn der Benchmark auf den eigenen Korpora seinen Nutzen
  zeigt** — und zwar gegen die bestehenden Baselines, ohne Regression an anderer Stelle.
- Zeigt er ihn nicht, bleibt er als betreiberseitig aktivierbare Option bestehen, mit dokumentiertem
  Messergebnis. Das ist kein Fehlschlag, sondern das Ergebnis.

**Stand nach #1050:** Der Qualitätsnutzen ist gezeigt (Abschnitt oben), die Voreinstellung bleibt
trotzdem „aus". Das ist kein Widerspruch, sondern die zweite Hälfte derselben Regel: Für eine
Aktivierungsempfehlung fehlt das Latenz-/Hardwareprofil, und ein Qualitätsgewinn, dessen Preis
niemand kennt, ist keine Entscheidungsgrundlage. Die Voreinstellung wird nach dieser Messung
gesetzt — nicht vor ihr. Da das Profil zurückgestellt ist (#1051), bleibt es bis auf Weiteres
beim Default „aus".

Dasselbe gilt für jede Teilentscheidung dieser Spezifikation, die eine Zahl trägt: Kompositazerlegung,
Kandidatenzahl, Fusionsgewichte, Eskalationsstufen.

---

## Messung und Abnahme

Der Messaufbau für alle Arbeitspakete ist eigenständig beschrieben in
**[Suchqualitäts-Benchmark](./retrieval-benchmark.md)**. Für diese Spezifikation sind
drei Punkte bindend:

1. **Kein Arbeitspaket gilt ohne Messung als abgenommen.** Der Nachweis läuft über den Benchmark, nicht
   über Augenschein an Demo-Fragen. Die bestehenden Regeln (Messvertrag
   [ADR-0012](../decisions/0012-messvertrag-retrieval-harness.md), Fehlerkriterium
   [ADR-0013](../decisions/0013-fehlerkriterium-retrieval-regression.md)) gelten unverändert.
2. **Gemessen wird die produktive Pipeline**, nicht die rohe Vektorsuche. Der heutige Harness misst
   `similaritySearch` direkt und läuft an Teilfragen-Zerlegung, Fusion, MMR und
   Dokument-Vervollständigung vorbei — diese Lücke muss vor Arbeitspaket 2 geschlossen sein, sonst misst
   der A/B-Vergleich nicht das, was Nutzende erleben.
3. **Die #938-Klasse ist ein eigenes Benchmark-Segment.** Fälle, in denen der gesuchte Begriff im
   Dokument wörtlich steht und die Vektorsuche ihn trotzdem verfehlt, sind der Existenzgrund dieser
   Spezifikation. Sie müssen einzeln ausgewiesen sein, nicht in einem Gesamtmittel verschwinden.

Zusätzlich zur Kennzahlmessung gilt die **Live-Abnahme an #938 selbst**: Drehbuch-Frage 6 muss als
`thomas.klein` eine Antwort mit Beleg auf `01_verwaltungsgebuehrensatzung.pdf` liefern. Ein
Kennzahlgewinn ohne diesen Fall wäre ein Zeichen dafür, dass etwas anderes gemessen wurde als das
Problem.

---

## Konfigurations-Ebenenmodell

Dieser Abschnitt ist ein Querschnitt über alle Arbeitspakete und die Antwort auf ein absehbares Risiko:
Hybrid-Suche und Reranking bringen zusammen leicht ein Dutzend neuer Zahlen mit — Kandidatenzahlen je
Pfad, Fusionsgewichte, Rerank-Schwellen, Analysekettenoptionen. Eine Software, die sie alle in eine
Oberfläche stellt, hat ihre Entscheidungen nicht getroffen, sondern an die Betreiberin weitergereicht.

### Die harte Regel

> **Jeder neue Parameter braucht eine Antwort auf: „Wer stellt das wann um, und woher weiß er, worauf?"**
> Gibt es diese Antwort nicht, wird der Wert ein fester Default und kein Parameter.

„Woher weiß er, worauf" ist der Teil, an dem die meisten Parameter scheitern — und das ist erwünscht.
Wer `mmr-lambda` verstellt, kann die Wirkung nicht beurteilen, ohne den Benchmark zu fahren; also gehört
der Wert nicht in eine Oberfläche.

### Die drei Ebenen

**Ebene 1 — benchmark-gehärtete interne Defaults.** Hier liegt der Großteil: Dämpfungskonstante der
Fusion, Kandidatenzahlen je Pfad, Rerank-Kandidatenmenge, Optionen der Analysekette, `mmr-lambda`,
Ähnlichkeitsschwelle. Diese Werte sind über Properties überschreibbar — für Entwicklung, Benchmark und
den seltenen begründeten Einzelfall — aber sie erscheinen **in keiner Administrationsoberfläche und in
keiner Beraterdokumentation**. Ihre Voreinstellung ist gemessen, und das Messergebnis steht in der
Spezifikation, nicht der Regler in der Oberfläche.

**Ebene 2 — Installationsentscheidungen.** Umgebungsvariablen bzw. Compose-Konfiguration, gesetzt beim
Aufsetzen: die Modellrollen einschließlich der neuen Rerank-Rolle (Endpunkt, Modellkennung, Schlüssel)
und die Sprache der Analysekette. Das Ziel ist eine **Handvoll Zeilen**, und der Weg dorthin sind
dokumentierte Profile statt einer Parameterliste:

```
# Profil „ohne GPU" — Hybrid-Suche, kein Reranking
OPAA_RERANK_ENABLED=false      # ausdrücklich aus, nicht bloß unkonfiguriert

# Profil „mit GPU" — Hybrid-Suche mit Reranking
OPAA_RERANK_ENABLED=true
OPAA_RERANK_BASE_URL=http://ollama:11434/v1
OPAA_RERANK_MODEL=bge-reranker-v2-m3
```

Die Profile sind der eigentliche Liefergegenstand dieser Ebene. Eine Betreiberin soll entscheiden „wir
haben eine GPU" und nicht „wir wählen eine Kandidatenmenge".

**Ein Profil ist mehr als seine Env-Zeilen.** Zum Profil „mit GPU" gehört die **Bereitstellung des
Modells**, und die ist der eigentliche Aufwand: welches Modell in welcher Fassung, wie es in den
Modellserver kommt, wie es aktualisiert wird. Verbindlich ist dabei der **Offline-Beschaffungsweg** —
eine Behörde ohne Internetzugang am Serverstandort kann kein Modell zur Laufzeit herunterladen. Das
Profil beschreibt deshalb auch, wie das Modellartefakt außerhalb bezogen, übertragen, geprüft und
lokal bereitgestellt wird; ein Profil, das einen Laufzeit-Download voraussetzt, ist für einen
erheblichen Teil der Zielinstallationen unbrauchbar (siehe
[Betrieb ohne Netzanbindung](./deployment-infrastructure.md#betrieb-ohne-netzanbindung)).

**Ebene 3 — je Wissensbibliothek, in der Oberfläche.** Metadaten und bibliotheksspezifische
Festlegungen. Rechtsquellen, Besprechungsnotizen und Tabellenwerke vertragen nicht dieselbe Behandlung —
das ist fachlich richtig und wird von Menschen entschieden, die den Bestand kennen. **Diese Ebene gehört
in eine eigene Spezifikation** (Konzept und Leitplanken in
[discussion-dateitypen-und-metadaten.md](../discussions/discussion-dateitypen-und-metadaten.md)) und wird
hier nur benannt, damit die Ebenenaufteilung vollständig ist.

### Was daraus für diese Spezifikation folgt

| Neuer Wert | Ebene | Begründung |
|---|---|---|
| Rerank-Endpunkt, -Modell, -Schlüssel | 2 | Hängt an der Hardware der Installation; die Betreiberin weiß, was sie hat |
| Sprache der Analysekette | 2 | Hängt am Bestand; heute faktisch Deutsch |
| Kandidatenzahl des Volltextpfads | 1 | Gemessen; niemand kann sie ohne Benchmark beurteilen |
| Rerank-Kandidatenmenge (~50) | 1 | dito |
| Rerank an/aus (`OPAA_RERANK_ENABLED`) | 2 | Expliziter Schalter, damit „aus" eine Absicht ist und kein unbemerkter Konfigurationsfehler |
| Kompositazerlegung an/aus | 1 | Gemessen; wird erst gebaut, wenn der Benchmark sie fordert |
| Fusionsgewichte | — | Kein Parameter. RRF ist tuningfrei; eine Gewichtung entsteht erst mit einer Messgrundlage |

---

## Die Administrationsseite „Suche & Indexierung"

### Zweck: sichtbar machen und diagnostizieren, nicht einstellen

Die Seite ist ausdrücklich **keine Reglerwand**. Was sie leisten muss, ist die Antwort auf die Frage, die
im Betrieb tatsächlich gestellt wird:

> „Warum sieht Nutzer X das Dokument Z nicht?"

Heute ist diese Frage nur durch Ausleiten der Kandidatenliste im Code beantwortbar — so wurde #938
diagnostiziert. Das ist für eine Behörde ohne Entwicklungsteam keine Option, und es ist der Grund, warum
Arbeitspaket 1 die Erklärbarkeit jeder Stufe verlangt.

### Was die Seite anzeigt

**Aktive Konfiguration**, als Statusanzeige und ohne Bearbeitungsmöglichkeit:

- die belegten Modellrollen — Chat, Einbettung, Reranking — jeweils mit Endpunkt, Modellkennung und
  Erreichbarkeit. Die Rerank-Rolle wird in drei unterscheidbaren Zuständen geführt: ausdrücklich
  abgeschaltet, aktiviert und erreichbar, aktiviert aber unbelegt oder nicht erreichbar. Der letzte
  Zustand ist eine Störungsmeldung, keine Fußnote. Der Zugangsschlüssel erscheint nie, auch nicht
  gekürzt (unverändert zur bestehenden Modellverwaltung).
- die **aktiven Suchpfade**: Vektor, Volltext, jeweils mit Zustand. Ein Volltextindex, der noch nicht
  über den ganzen Bestand aufgebaut ist, ist hier sichtbar und nicht erst an schlechten Antworten
  spürbar.
- der **Indexstatus** je Bibliothek: Zahl der Dokumente und Chunks, letzter Lauf, Rückstand, Zustand
  von Vektor- und Volltextindex — einschließlich des **Füllstands des Volltext-Backfills** aus
  [Arbeitspaket 2a](#arbeitspaket-2a-backfill-des-bestands), aus derselben Datenquelle, die auch den
  Alarm „Volltextpfad inaktiv oder unvollständig" auslöst.

### Das Diagnosewerkzeug

Eine Testfrage wird eingegeben, in einem gewählten Berechtigungskontext ausgeführt, und die Seite zeigt
**jede Pipeline-Stufe einzeln**. Der voreingestellte Berechtigungskontext ist ein **Rechteprofil** —
eine Rolle mit der zugehörigen Bibliotheksmenge —, nicht eine Person:

```
Testfrage:  "Was gilt bei Gebührenbefreiung wegen Bedürftigkeit?"
Sicht als:  Rechteprofil „Sachbearbeitung Bürgerbüro" (Satzungen & Gebührenordnungen, Formulare)

1  Suchbereich          2 Bibliotheken (Satzungen & Gebührenordnungen, Formulare)
2  Teilfragen           "Gebührenbefreiung Bedürftigkeit" · "Voraussetzungen Befreiung Verwaltungsgebühr"
3  Vektorsuche          25 Kandidaten je Teilfrage   → beste 5 mit Score
4  Volltextsuche        18 Kandidaten je Teilfrage   → beste 5 mit Rang und Treffertermen
5  Fusion (RRF)         86 Listeneinträge → 62 Kandidaten, davon 50 im Budget
6  Reranking            50 → 8                       → Reranker-Score je Kandidat, Verworfene sichtbar
7  Dokument-Verv.       8 Chunks aus 5 Dokumenten    → welcher Chunk wodurch verdrängt wurde
   Endauswahl           mit Begründung je Chunk: über welchen Pfad hereingekommen, wo verloren
```

Für ein Dokument, das **nicht** in der Endauswahl steht, ist die entscheidende Angabe die letzte Zeile:
Wurde es gar nicht gefunden (dann ist es ein Indexierungs- oder Chunking-Problem), oder wurde es
gefunden und in einer bestimmten Stufe verdrängt (dann ist es ein Ranking-Problem)? Das ist der
Unterschied zwischen zwei völlig verschiedenen Abhilfen, und heute lässt er sich nur mit Codezugriff
feststellen.

### Berechtigungs-Leitplanken

> **Stand: gebaut** ([#1052](https://github.com/criew/opaa/issues/1052)). Das Befugnis- und
> Protokollmodell liegt im Backend-Paket `io.opaa.diagnosticaccess`; die Regeln unten bleiben die
> maßgebliche Fassung. Drei Festlegungen der Umsetzung, die dieser Abschnitt offenließ:
>
> - **Geltungsbereich** ist eine Gruppe der Art `ORG_UNIT` (die Organisationseinheit aus dem
>   Verzeichnisabgleich); **Gültigkeitsdauer** ist auf zwölf Monate je Vergabe begrenzt. Beides sind
>   `NOT NULL`-Spalten mit zusätzlicher `CHECK`-Bedingung — ein unbefristetes, bereichsloses
>   Dauerrecht ist nicht speicherbar, nicht nur nicht anlegbar.
> - **Die Befugnis zur Protokollauswertung** ist die vorhandene Rolle `AUDITOR` (die „benannten
>   Stellen" aus (h)); sie und „Sicht als" haben keinerlei Ableitungsbeziehung, und `SYSTEM_ADMIN`
>   trägt keine von beiden.
> - **„Standardmäßig gesperrt"** ist als Voreinstellung der Sperre selbst umgesetzt: jede
>   Bibliothek — auch jede bereits vorhandene — ist diagnosegesperrt, bis die zuständige Stelle die
>   Sperre bewusst aufhebt. **Wirksam wird die Sperre erst mit dem Personenkontext** — bis dahin
>   greift sie auf keinem ausgelieferten Diagnosepfad; siehe die Klarstellung zur Reichweite bei (e).
>   Der Grundzustand steht trotzdem schon, damit zu diesem Zeitpunkt keine Bibliothek versehentlich
>   offen ist. Eine Kategorienerkennung („ist das ein Personalvertretungsbestand?")
>   gibt es nicht und kann es für Altbestände nicht geben; die Sperre als Grundzustand deckt die
>   vier genannten Bestände zuverlässig ab und fällt im Zweifel zugunsten des Schutzes aus.
> - **Reichweite der Zusage aus (e)** — die Sperre löst nur, wer einen `OWNER`-Grant auf der
>   Bibliothek hält, den er sich nicht selbst gegeben hat (oder wer die benannte zuständige Stelle
>   ist: Eigentümerperson bzw. Mitglied der Eigentümergruppe). Damit ist der Zwei-Schritt-Weg
>   geschlossen, sich über die Administratorbefugnis des Grant-Endpunkts erst selbst `OWNER` zu
>   geben und dann als „Zuständige Stelle“ zu entsperren — auch dann, wenn dazu nur ein bereits
>   vorhandener Fremd-Grant angehoben würde, denn `granted_by_user_id` wird beim Rollenwechsel auf
>   die ändernde Person fortgeschrieben. Fortgeschrieben wird bei einer echten Rollenänderung **und
>   bei der Wiederbelebung einer bereits abgelaufenen Berechtigung**: Eine abgelaufene Berechtigung
>   zählt für die Zusage nicht mit, also verschafft die Rolle, wer sie wieder wirksam macht — auch
>   wenn die Rolle dabei unverändert bleibt. Ohne diesen zweiten Fall stünde der Weg über die
>   *abgelaufene* Fremd-Berechtigung offen: Frist neu setzen, Rolle unverändert lassen, weiterhin als
>   fremd vergeben gelten. Die Verlängerung einer **noch laufenden** Berechtigung macht dagegen
>   niemanden zum eigenen Vergeber; sie verschafft nichts, was nicht schon galt.
>
>   Für Zeilen, deren Rolle bereits vor dieser Änderung angehoben wurde, zieht Changeset `008` den
>   Vergeber einmalig aus der Rechtehistorie (`asset_grant_history`) nach; ohne diesen Nachzug bliebe
>   der Weg für den Altbestand offen. **Der Nachzug deckt den Wiederbelebungsfall nicht ab:** `008`
>   rekonstruiert ausschließlich aus Intervallen, deren Rolle sich von der des Vorgängerintervalls
>   unterscheidet. Eine Wiederbelebung vor dem Deploy — jemand hat die Frist einer abgelaufenen
>   Fremd-Berechtigung verlängert, ohne die Rolle zu ändern — bleibt damit im Bestand mit dem alten
>   Vergeber stehen. Die Laufzeitkorrektur heilt das nicht mit: Sie greift erst, wenn dieselbe Zeile
>   nach dem Deploy erneut angefasst wird. Rekonstruierbar wäre der Fall (`asset_grant_history` führt
>   `expires_at` und `valid_from`); dass er nicht nachgezogen wird, ist eine bewusste offene Stelle
>   für den Altbestand.
>
>   **Was die Administration weiterhin allein erreicht**, ohne dass eine zweite Person mitwirkt:
>   Gehört die Bibliothek einer Gruppe, die keine `ORG_UNIT` ist, kann ein `SYSTEM_ADMIN` sich über
>   `POST /api/v1/groups/{id}/members` selbst in diese Eigentümergruppe eintragen — die
>   Gruppenverwaltung kennt keinen Selbstausschluss, und `rejectOrgUnit` greift nur für
>   Verzeichnis-Organisationseinheiten. Danach ist er „benannte zuständige Stelle“ und löst die
>   Sperre. Die Eigentümerperson (`ownerUserId`) ist von diesem Weg nicht betroffen: sie ist
>   unveränderlich. Ebenfalls nicht ausgeschlossen bleibt, dass die Administration einem anderen,
>   benannten Konto `OWNER` gibt, das die Sperre dann löst. Beide Wege stehen vollständig im
>   Protokoll, keiner ist verhindert; der Selbstausschluss bei der Gruppenmitgliedschaft ist ein
>   eigener Eingriff mit eigener Abwägung und als Folgearbeit in
>   [#1124](https://github.com/criew/opaa/issues/1124) vorgemerkt. Solange er fehlt, gilt das
>   Abnahmekriterium „nicht die Administration" aus #1052 als **nicht erfüllt**.
>
> Das Protokoll liegt in einer eigenen Tabelle (`diagnostic_context_log`) unter derselben
> Eigentümertrennung wie `audit_log` (ADR-0015), nicht als weiterer Ereignistyp darin: seine
> Aufbewahrung beträgt nach (i) zwölf Monate, die von `audit_log` 12–120 — und eine kürzere Frist
> für eine Teilmenge der Zeilen wäre nur mit einem zeilenweisen `DELETE` erreichbar, das es auf
> `audit_log` nicht geben darf.

Ein Werkzeug, das zeigt, was ein anderer Mensch sieht, ist ein Berechtigungswerkzeug — und in einer
Behörde zugleich ein mitbestimmungsrelevantes. Die folgenden Regeln sind deshalb nicht verhandelbar;
sie sind Baubedingung, nicht Ausbaustufe.

**(a) Diagnose liest nie echte Chat-Historien.** Die Diagnose läuft **immer** als frisch eingegebene
Testfrage im gewählten Berechtigungskontext. Es gibt keinen Weg von dieser Seite in die tatsächlichen
Gespräche eines Nutzers — weder zum Nachvollziehen einer Beschwerde noch zur Fehlersuche. Chats sind
space-eigener, zunächst privater Inhalt (siehe
[Die Grundregel](./spaces-and-assets.md#die-grundregel-zunächst-privat-sichtbar-durch-teilen)); eine
Administrationsseite, die sie einsehbar macht, würde diese Zusage aushebeln, und zwar an der
unauffälligsten denkbaren Stelle. Die „Sicht als"-Auswahl bestimmt ausschließlich den **Rechtekontext**
der Suche, nicht den Zugriff auf Inhalte dieser Person.

**(b) Die Diagnose beantwortet den Jetzt-Zustand, nicht die Vergangenheit.** Sie ist ein
Fehlersuchwerkzeug: Sie zeigt, was ein Rechtekontext **in diesem Moment** finden würde.
**Sie ist ausdrücklich kein Zugriffshistorien-Nachweis** — die Frage „worauf hatte X am 3. März
Zugriff?" beantwortet sie nicht und darf nicht mit ihr beantwortet werden. Wer eine solche Auskunft
braucht, braucht ein anderes Werkzeug mit eigener Rechtsgrundlage.

**(c) „Sicht als" ist eine eigene, geregelte Befugnis.** Die Diagnose zeigt dem Ausführenden
Dokumenttitel und Fundstellen, die für den Zielkontext sichtbar sind — und je nach gewähltem Kontext
damit auch Dokumente, die der **Ausführende selbst** nicht lesen dürfte. Das ist unvermeidlich, wenn das
Werkzeug seinen Zweck erfüllen soll, und deshalb wird es als das behandelt, was es ist. Verbindlich
gilt:

- Die Befugnis MUSS **benannt und einzeln vergeben** werden; sie wird nicht aus „ist Administrator"
  abgeleitet.
- Die Befugnis MUSS einen **Geltungsbereich** (Organisationseinheit) und eine **Gültigkeitsdauer**
  tragen. Ein unbefristetes, bereichsloses Dauerrecht ist nicht zulässig.
- Die Befugnis „Sicht als" und die Befugnis zur **Auswertung des Protokolls** MÜSSEN getrennt vergeben
  werden. Wer diagnostiziert, kontrolliert nicht sich selbst.
- Eine Diagnose im **eigenen** Rechtekontext ist von alldem nicht betroffen; sie zeigt nichts, was die
  ausführende Person nicht ohnehin sehen darf. Dasselbe gilt für Rechteprofile, die keiner Person
  zugeordnet sind.

**(d) Der Personenkontext ist die Ausnahme.** Die Voreinstellung von „Sicht als" ist ein Rechteprofil
(Rolle und Bibliotheksmenge). Wird ausnahmsweise der Rechtekontext einer **konkreten Person**
eingenommen, MUSS beim Aufruf eine **Freitextbegründung** angegeben werden, die im Protokolleintrag
mitgeführt wird. Ein Personenkontext ohne Begründung wird nicht ausgeführt.

**(e) Diagnosegesperrte Bibliotheken.** Eine Bibliothek KANN als diagnosegesperrt gekennzeichnet
werden. Für sie ist „Sicht als" ausgeschlossen: keine Treffer, keine Dokumenttitel, keine Zahlen —
die Diagnose weist sie als „gesperrter Suchbereich" aus und liefert daraus nichts.

- Bestände der **Personalvertretung**, der **Schwerbehindertenvertretung**, der **Gleichstellung**
  sowie **Personalvorgänge** sind standardmäßig gesperrt.
- Die Sperre setzt und löst die **jeweils zuständige Stelle selbst**, nicht die Administration. Eine
  Administratorbefugnis, die eine fremde Sperre aufheben kann, hebt den Schutz auf.

> **Klarstellung zur Reichweite (Koordinator mit Maintainer-Freigabe, 02.09.2026).** Der Wortlaut oben
> sagt „für sie ist **‚Sicht als'** ausgeschlossen". Aufgefallen im Architektur-Review zum Abschluss
> dieses Epics: Der ausgelieferte Diagnosepfad (`SearchDiagnosisService`) löst seinen Suchbereich
> selbst auf und zieht gesperrte Bibliotheken **nicht** ab — der Durchsetzungscode in
> `ForeignDiagnosticContextService` hat außerhalb seines Pakets keinen Aufrufer.
>
> Aufgelöst wird das entlang derselben Linie wie bei (f): **Die Sperre gilt für den Personenkontext,
> nicht für Läufe im eigenen Rechtekontext und nicht für Rechteprofile.** Für diese beiden greift
> (c) — sie zeigen nichts, was die ausführende Person nicht ohnehin sehen darf: Die
> Administrationsseite ist Systemadministratoren vorbehalten, und `LibraryAccessService#effectiveRole`
> lässt diese Rolle auf jede Bibliothek ihrer Organisation als `OWNER` durch; dieselben Dokumenttitel
> stehen ihnen bereits in der Bibliotheksverwaltung offen. Chunk-Inhalte gibt die Diagnose überhaupt
> nicht heraus.
>
> **Damit ist die Sperre heute wirkungslos, und das ist gewollt** — sie wirkt erst, wenn „Sicht als
> (Person)" ausgeliefert wird. Der Grundzustand `diagnostics_locked = true` bleibt bestehen, damit
> zu diesem Zeitpunkt keine Bibliothek versehentlich offen ist; der Schutz greift also ab dem ersten
> Tag des Personenkontexts, nicht erst nach einer Nachpflege.
>
> **Wer „Sicht als (Person)" anschließt** (siehe [#1150](https://github.com/criew/opaa/issues/1150)),
> **führt den Pfad zwingend über `ForeignDiagnosticContextService#execute`** — dort und nur dort
> laufen Befugnisprüfung, Sperrenabzug, Pflichtbegründung und Protokolleintrag zusammen. Ein zweiter
> Auflösungsweg für den Suchbereich wäre genau die Lücke, die dieser Abschnitt schließen soll.

**(f) Protokollinhalt.** Jede Ausführung in einem fremden Rechtekontext MUSS einen Protokolleintrag
nach den Regeln der [Protokollablage](../decisions/0015-eigentuemertrennung-protokollablage.md)
erzeugen. Er enthält verbindlich:

- die ausführende Person,
- den Zielkontext (Rechteprofil oder Person),
- den Zeitpunkt,
- die Testfrage,
- Zahl und Kennungen der angezeigten Fundstellen,
- den verwendeten Rechte-Snapshot,
- die Begründung, sofern ein Personenkontext eingenommen wurde.

> **Klarstellung zum Rechteprofil-Kontext (Koordinator, 01.09.2026, mit Maintainer-Freigabe).** Die
> Aufzählung oben nennt „Rechteprofil oder Person" als Zielkontext, während (c) den Profilkontext
> ausdrücklich von den Leitplanken ausnimmt („Dasselbe gilt für Rechteprofile, die keiner Person
> zugeordnet sind"). Das ist ein Widerspruch innerhalb dieses Abschnitts, aufgefallen im Review zu
> [#1118](https://github.com/criew/opaa/pull/1118). Er wird zugunsten von (c) aufgelöst: **Die
> Protokollpflicht aus (f) gilt für den Personenkontext; für Rechteprofile ruht sie**, bis das
> Befugnis- und Protokollmodell aus [#1052](https://github.com/criew/opaa/issues/1052) steht.
>
> Begründung: Ein Rechteprofil ist keiner Person zugeordnet, eine Profil-Diagnose ist deshalb keine
> Aussage über einen Menschen und aus Mitbestimmungssicht nicht das, wogegen diese Leitplanken
> geschrieben sind. Hinzu kommt, dass die Diagnose dem Ausführenden im Profilkontext nichts zeigt, was
> er nicht ohnehin sehen darf: Die Seite ist Systemadministratoren vorbehalten, und
> `LibraryAccessService` lässt diese Rolle auf jede Bibliothek ihrer Organisation als `OWNER` durch —
> dieselben Dokumenttitel stehen ihnen bereits in der Bibliotheksverwaltung offen. Chunk-Inhalte gibt
> die Diagnose überhaupt nicht heraus. Der Zweck von (f) ist hier also die Nachvollziehbarkeit der
> Befugnisausübung, nicht der Geheimnisschutz — und eine Befugnis, die über das Bestandsrecht des
> Systemadministrators nicht hinausgeht, trägt diese Pflicht noch nicht.
>
> **Diese Ruhensregel endet mit #1052.** Sobald das Protokollmodell steht, ist erneut zu entscheiden,
> ob Profil-Läufe mitprotokolliert werden; die Kosten dafür sind dann gering, weil die Ablage existiert.

**(g) Zweckbindung des Protokolls.** Das Protokoll ist unveränderlich und dient ausschließlich der
Nachvollziehbarkeit einzelner Befugnisausübungen. Daraus folgt:

- Es DARF in **keiner** Oberfläche und in **keinem** Export nach Zielperson gruppiert, gezählt oder als
  Statistik dargestellt werden.
- Eine Auswertung „Diagnosen je Nutzer" DARF es nicht geben; ein Bezug zur Leistungs- oder
  Verhaltensbewertung ist ausgeschlossen.
- Eine Auswertung erfolgt ausschließlich **einzelfall- und anlassbezogen**.

**(h) Einsichtsrecht.** Jede Person MUSS jederzeit einsehen können, wann und von wem ihr Rechtekontext
eingenommen wurde — als eigene Ansicht in der Anwendung, ohne Antragsweg und ohne Beteiligung Dritter.
Die Einsicht in das **Gesamtprotokoll** steht nur benannten Stellen zu (Datenschutzbeauftragte,
Personalvertretung); **Fachvorgesetzte gehören ausdrücklich nicht dazu.**

**(i) Aufbewahrung.** Protokolleinträge werden **12 Monate** aufbewahrt und danach automatisch und
nachweisbar gelöscht. Die Frist ist konfigurierbar, die Löschung selbst nicht abschaltbar.

**(j) Keine Speicherung fremder Diagnoseergebnisse.** Das **Ergebnis** einer Diagnose in einem fremden
Rechtekontext wird nie gespeichert, sondern nur einmalig angezeigt. Der Vergleich zweier Läufe vor und
nach einer Änderung — fachlich wünschenswert — ist deshalb nur im **eigenen** Rechtekontext und für
**Rechteprofile** möglich. Protokolliert wird die Ausübung der Befugnis, nicht der Inhalt dessen, was
eine andere Person sehen kann.

---

## Reihenfolge

Die Arbeitspakete sind bewusst so geschnitten, dass jedes für sich einen Nutzen hat und einzeln messbar
ist.

| # | Paket | Abhängig von | Nutzen allein |
|---|---|---|---|
| 0 | Benchmark misst die produktive Pipeline | — | Vorbedingung; ohne sie ist nichts abnehmbar |
| 1 | Pipeline als benannte Stufen | 0 | Kein fachlicher; Voraussetzung für 2, 5 und die Diagnose |
| 2 | Volltextspalte, Index und **Backfill des Bestands** (AP 2a) | 1 | Der Volltextpfad wird überhaupt erst vollständig speisbar; Füllstand wird sichtbar |
| 3 | Lexikalischer Suchpfad + Fusion (AP 2/AP 3) | 1, 2 | Löst die #938-Klasse; wirkt ohne jedes weitere Modell — **gebaut (#1048/#1049)**, gemessen in AP 3 |
| 4 | Rerank-Modellrolle + Reranking-Stufe | 1, 3 | Präzision auf der fusionierten Menge; nur mit Modell |
| 5 | Admin-Seite „Suche & Indexierung" | 1, Befugnis-/Protokollmodell | Diagnosepfad; wird mit jedem weiteren Paket wertvoller |
| 6 | Latenz-/Hardwareprofil auf Referenzhardware — **zurückgestellt (#1051)** | 4 | Voraussetzung jeder Aktivierungsempfehlung für Reranking; ohne das Profil bleibt Reranking voreingestellt aus |

Paket 2 steht vor Paket 3, weil ein Volltextpfad über einem halb befüllten Index falsche Messwerte und
falsche Antworten zugleich erzeugt. Aufgenommen in die Fusion wird der Pfad erst, wenn der Backfill
einer Bibliothek abgeschlossen ist.

Paket 5 hängt nicht an 3 und 4 — die Diagnose ist auch für die heutige Pipeline schon nützlich, und ein
früher Bau bedeutet, dass die Pakete 3 und 4 gegen ein vorhandenes Diagnosewerkzeug entwickelt werden
statt gegen Log-Ausgaben. Es hängt aber zusätzlich am **Befugnis- und Protokollmodell** aus den
[Berechtigungs-Leitplanken](#berechtigungs-leitplanken), und daraus folgt ein Lieferschnitt innerhalb
des Pakets:

> **„Sicht als (Person)" wird nicht ausgeliefert, solange Befugnis mit Geltungsbereich und Befristung,
> Protokoll, Einsichtsrecht und Löschfrist nicht umgesetzt sind.** Die Admin-Seite ist ohne diesen Teil
> vollständig nutzbar — im eigenen Rechtekontext und für Rechteprofile — und liefert dort bereits den
> überwiegenden Diagnosenutzen.

---

## Integrationspunkte

- **[Wissensschicht und Retrieval](./data-indexing-rag.md)** — Zielbild und Quelle der Wahrheit für die
  Stellschrauben-Tabelle. Diese Spezifikation setzt dessen Abschnitte
  [Hybride Suche](./data-indexing-rag.md#hybride-suche) und
  [Reranking](./data-indexing-rag.md#reranking) um; die dort geführten Parameter werden hier nicht
  dupliziert, sondern ergänzt.
- **[Retrieval-Algorithmus (Ist-Stand)](./retrieval-algorithm.md)** — der heutige Ablauf, den
  Arbeitspaket 1 umstrukturiert und die Pakete 2 und 3 erweitern. Das Dokument wird mit jedem Paket
  nachgeführt; es bleibt die Beschreibung des **gebauten** Stands.
- **[Modelle und zentrale Steuerung](./llm-integration.md)** — die Rerank-Rolle ist eine Modellrolle im
  dortigen Schichtenmodell und erbt dessen Zusagen: lokale Modelle als Voreinstellung, kein
  automatisches Ausweichen nach außen, verschlüsselte und nicht rücklesbare Zugangsdaten,
  protokollierte Änderungen.
- **[Spaces, Assets und Zugangskontrolle](./spaces-and-assets.md)** — der Rechtefilter, den der
  Volltextpfad identisch trägt, und die Privatheit der Chats, an der die Diagnose-Leitplanke (a) hängt.
- **[Suchqualität messbar machen](./search-quality-evaluation.md)** und
  **[Suchqualitäts-Benchmark](./retrieval-benchmark.md)** — Messaufbau, Korpora und
  Abnahmekriterien für jedes Arbeitspaket.
- **[Zugangskontrolle](./access-control.md)** — Protokollierung der Diagnose im fremden Rechtekontext,
  Vergabe der „Sicht als"-Befugnis mit Geltungsbereich und Befristung sowie die davon getrennte
  Befugnis zur Protokollauswertung.
- **[Deployment und Infrastruktur](./deployment-infrastructure.md)** — Referenzhardware und
  Lastannahmen für das Latenzprofil, das nächtliche Zeitfenster für den Backfill und der
  Offline-Beschaffungsweg für Rerank-Modelle.
- **[ADR-0014](../decisions/0014-produktausrichtung-oeffentliche-verwaltung.md)** — pgvector als
  einziger Vektorspeicher; die Postgres-native Volltextsuche ist die konsequente Fortsetzung derselben
  Abwägung für den lexikalischen Pfad.

---

## Bewusst nicht gebaut

Jede Zeile ist eine Entscheidung, keine Auslassung. Ausführliche Begründungen mit Quellen stehen in
[discussion-retrieval-roadmap-opaa.md](../discussions/discussion-retrieval-roadmap-opaa.md#bewusst-nicht-verfolgen).

- **Zweite Suchengine (OpenSearch, Elasticsearch, Vespa) als Teil dieser Umsetzung.** Sie steht als
  [Eskalationsstufe 2](#eskalationsstufen-mit-eintrittsbedingung) unter einer gemessenen
  Eintrittsbedingung, nicht im Lieferumfang — der doppelte Rechtefilter und das Synchronisationsrisiko
  sind der Preis, und er wird nicht auf Verdacht gezahlt.
- **SPLADE / ELSER (learned sparse retrieval).** Für Deutsch nicht belegt und lizenzseitig verbaut
  (CC-BY-NC bzw. Elastic-Platinum); den lexikalischen Bedarf deckt der PostgreSQL-Volltextpfad.
- **ColBERT / Late Interaction.** pgvector beherrscht kein natives MaxSim (Konflikt mit ADR-0014), die
  Speicherkosten liegen beim 10- bis 50-Fachen, und den Präzisionsgewinn bei Kennungen erreicht der
  Kennungsschutz des Volltextpfads billiger.
- **Multi-Query-Expansion als Voreinstellung.** Verschlechtert ohne nachgeschalteten Reranker
  nachweislich die Präzision (ARAGOG); den legitimen Kern deckt die vorhandene Teilfragen-Zerlegung ab.
  Nach Arbeitspaket 3 gegebenenfalls als reranker-gestützte Recall-Stufe neu zu bewerten.
- **LLM-as-Reranker im Antwortpfad.** Destillierte Cross-Encoder erreichen dieselbe Qualität bei bis zu
  173-facher Geschwindigkeit; im Antwortpfad ist das kein Abwägen, sondern ein Fehler. In der
  Offline-Evaluation als Judge dagegen durchaus nützlich.
- **`pg_trgm` für Tippfehler- und Teilwortsuche.** Zurückgestellt bis zu einem gemessenen Bedarf;
  Tippfehler normalisiert heute bereits die Teilfragen-Zerlegung nebenbei.

---

## Offene Punkte

Nur Fragen, die tatsächlich offen sind und vor oder während der Umsetzung entschieden werden müssen.

- **Kompositazerlegung: ispell-Wörterbuch oder german-decompounder-Ansatz?** Erst nach der Messung
  entscheidbar — und nur, wenn das Komposita-Segment des Benchmarks überhaupt eine Lücke zeigt. Beide
  Wege haben unterschiedliche Pflegekosten für das Wörterbuch.
- ~~**Wie wird der Volltextindex bei einer Änderung der Analysekette nachgezogen?**~~ Beantwortet
  mit #1047/#1048: `FullTextChunkStore#CURRENT_TSV_VERSION` markiert jede geänderte `tsvector`-Form,
  `FullTextBackfillService`/`FullTextBackfillScheduler` ziehen jede Zeile unterhalb der aktuellen
  Version in kleinen Chargen nach (Standard 200 Chunks je 5-Sekunden-Tick), ohne erneutes Einbetten.
  #1130 belegt diesen Weg erstmals in der Praxis mit einem Bump, der den gesamten Altbestand betrifft
  (neues Muster in `FullTextIdentifiers` für E-Mail-Adressen, Version 3 → 4).

  **Die tatsächliche Wirkung ist gröber als ein Zeilenfilter:** `FullTextBackfillGate` (siehe oben,
  "Reihenfolge") nimmt eine Bibliothek erst dann wieder in den lexikalischen Suchbereich auf, wenn
  ihr Backfill **vollständig** ist — `FullTextSearchStage` fragt `searchableLibraries(...)` und lässt
  jede noch nicht vollständige Bibliothek ganz aus der Fusion heraus, nicht nur die einzelnen noch
  veralteten Zeilen. Ein Versionssprung markiert jede Zeile jeder Bibliothek als veraltet, also
  verlässt in dem Moment **der gesamte Bestand** den lexikalischen Pfad, nicht nur die noch nicht
  bearbeiteten Chunks — exakt die Konsequenz aus "Ein Volltextpfad, der nur die Hälfte des Bestands
  sieht, ist schlimmer als keiner" (siehe oben), hier auf die Bump-Situation angewandt: keine
  graduelle Verschlechterung, sondern ein harter Rückfall auf reines Vektor-Retrieval für alles, bis
  jede Bibliothek einzeln durchgelaufen ist. Bei rund 1 Mio. Chunks bei Standardwerten (144.000
  Chunks/h) etwa sieben Stunden. Danach wird jede Bibliothek einzeln wieder aufgenommen, sobald ihr
  eigener Nachlauf fertig ist — keine globale Wiederkehr auf einen Schlag.

  **Voraussetzung für einen Bump, der den gesamten Bestand betrifft: #1093** (Gift-Chunk-Isolation)
  muss vorher gemergt sein — ohne sie hält ein einzelner kaputter Chunk den Fortschritt einer
  Bibliothek an, bis der Scheduler-Backoff die Ticks für den Rest der Prozesslaufzeit stoppt; diese
  eine Bibliothek bliebe dann bis zum nächsten Neustart vollständig außerhalb des lexikalischen
  Pfads statt nur vorübergehend.
- **Wirkt der Kontextpräfix aus Contextual Chunking (#933/#940) auch in den Volltextindex?**
  Anthropics „contextual BM25" spricht dafür, und die Roadmap sieht es in Phase 2a vor. Es ist aber eine
  eigene Messung wert: Ein Titelpräfix in jedem Chunk verändert die Termstatistik des ganzen Index.
- **Bekommt die Rerank-Rolle eine Mindestschwelle?** Das Zielbild sieht eine Schwelle vor, unterhalb
  derer eine Passage nicht mehr als Beleg taugt (siehe
  [Reranking](./data-indexing-rag.md#reranking)). Sie hätte unmittelbare Wirkung auf die Belegvalidierung
  — eine zu hohe Schwelle erzeugt Antworten ohne Fundstelle. Erst mit gemessenen
  Reranker-Score-Verteilungen entscheidbar.
- **Wie hoch darf der Aufwand des Erklärprotokolls im Regelbetrieb sein?** Erzeugt wird es immer
  (Pflicht-Rückgabewert der Stufenschnittstelle), ausgewertet nur in der Diagnose. Offen ist, wie
  detailliert es im Regelfall ausfallen darf, ohne die Antwortzeit spürbar zu belasten — eine
  Messfrage, keine Entwurfsfrage. Ob ein Ergebnis **gespeichert** werden darf, ist dagegen entschieden:
  im eigenen Kontext und für Rechteprofile ja, in einem fremden Personenkontext nie (siehe
  [Berechtigungs-Leitplanken](#berechtigungs-leitplanken)).
- ~~**Der Schwellenwert X der Eskalationsstufe 1.**~~ Entschieden mit #1048: **X = 0,80**, festgelegt vor
  dem ersten Variantenvergleich (siehe
  [Eskalationsstufen](#eskalationsstufen-mit-eintrittsbedingung)).
