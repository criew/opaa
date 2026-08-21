# Quelle: Domäne Sehenswürdigkeiten in europäischen Großstädten

| | |
|---|---|
| **Quelle** | [Wikidata](https://www.wikidata.org) (SPARQL-Endpunkt) |
| **Lizenz** | CC0-1.0 (gemeinfrei, keine Attributionspflicht) |
| **Abrufdatum** | 2026-08-21 |
| **Eingefrorene Rohdaten** | [`../../generator/frozen/`](../../generator/frozen/), siehe dort `SOURCE.md` für die vollständigen Abfragen, Auswahlregeln und Fallstrick-Entscheidungen (Issue #234) |
| **Dokumente** | 200 (`city-0001_*.md` … `city-0200_*.md`), sortiert nach Rang (Einwohnerzahl, absteigend) |
| **Sampling** | Kein Sampling im statistischen Sinn — deterministische Auswahlregel (Einwohnerzahl + Nebenbedingung „mindestens eine dokumentierte Sehenswürdigkeit"), siehe `../../generator/frozen/SOURCE.md` |

Ein Quellenhinweis wird geführt, obwohl CC0-1.0 ihn nicht verlangt — dasselbe Verfahren wie bei
`comic-characters` (siehe dort).

## Wie diese Dateien entstanden sind

Erzeugt durch
[`eval/generator/generate_city_landmarks_corpus.py`](../../generator/generate_city_landmarks_corpus.py)
aus den eingefrorenen Wikidata-Rohdaten unter `../../generator/frozen/`. Kein Netzzugriff beim Lauf
selbst — der Generator verifiziert vorab den SHA-256 jeder eingefrorenen Datei. Zwei Läufe erzeugen
byte-identische Ausgabe (per `diff -rq` belegt, siehe PR-Beschreibung von #234).

## Integritätsprüfung

```bash
cd eval/corpus/city-landmarks
sha256sum -c MANIFEST.sha256
```

## Umfang und Größenverteilung

Tatsächlich gemessen (nicht geschätzt), Stand des letzten Generator-Laufs:

| | Bytes |
|---|---|
| Minimum | 5.534 |
| Median | 11.809 |
| Maximum | 34.103 |
| Gesamtgröße | ca. 2,83 MB |

**Abweichung von der ursprünglichen Abschätzung (1,2–2,4 MB, Issue #234 „Prüfpunkt: Korpus-Ablage"):**
Die tatsächliche Größe liegt darüber, weil die Zielgröße "6–12 KB je Dokument" allein aus den
verfügbaren Sehenswürdigkeiten-Fakten nicht durchgängig erreichbar war (siehe unten) und stattdessen
über zusätzlichen, weiterhin quellenbasierten Fließtext (Städtevergleich zu Rang-Nachbarn im Korpus)
kompensiert wurde. Der Gesamtumfang bleibt mit rund 2,83 MB (zusammen mit den rund 1,9 MB von
`comic-characters`: rund 4,7 MB) weiterhin deutlich unter der 25-MB-Prüfschwelle aus ADR-0011.

**Chunk-Zahl-Verteilung** (echter `TokenTextSplitter`-Lauf, `chunkSize=1000`, `chunkOverlap=100` —
Docker-freier Trockenlauf über `io.opaa.eval.CityLandmarksChunkSizeDryRunTest`, siehe PR-Beschreibung
für den vollständigen `checkRetrievalBaseline`-Nachweis): Minimum 3, Median 5, Maximum 11 Chunks je
Dokument — die Domänen-Vorgabe „mindestens 3 Chunks je Dokument" (#721/#234) ist für alle 200
Dokumente erfüllt.

## Bekannte Eigenschaften und Grenzen dieses Korpus

- **Sehenswürdigkeiten-Dichte ist ungleich verteilt.** Median 3 Sehenswürdigkeiten je Stadt,
  Maximum 12 (Deckelung im Generator), Minimum 1 — siehe `../../generator/frozen/SOURCE.md`,
  Abschnitt „Bekannte Einschränkungen dieser eingefrorenen Abfragen" für die Gründe (Wikidata pflegt
  Sehenswürdigkeiten mit direktem Ortsbezug für kleinere Städte deutlich lückenhafter als für
  Großstädte).
- **Der Städtevergleich zu Rang-Nachbarn (Fließtext, keine Frontmatter-Angabe) ist textlich
  repetitiv** (eine Serie kurzer, strukturgleicher Vergleichssätze je Nachbarstadt). Er ist bewusst
  hinzugefügt worden, um die Domänen-Vorgabe „mindestens 3 Chunks je Dokument" auch für Städte mit
  wenigen dokumentierten Sehenswürdigkeiten zuverlässig zu erreichen (siehe PR-Beschreibung, Abschnitt
  „Bekannte Einschränkung: Textqualität"). Für die Golden-Fallauswahl (`landmark_detail`,
  `boundary_span`, `cross_chunk`) wurden bevorzugt die inhaltlich dichteren Sehenswürdigkeiten-Absätze
  verwendet, nicht die Rang-Vergleichssätze.
- **Grammatisches Geschlecht wird vermieden, nicht aufgelöst.** Wikidata liefert keine
  grammatische-Genus-Angabe für Sehenswürdigkeiten-Namen; der Generator formuliert deshalb
  durchgängig ohne vorangestellten bestimmten Artikel vor Eigennamen ("Brandenburger Tor befindet
  sich in …" statt "Das Brandenburger Tor …").
- Wie bei `comic-characters`: nur strukturierte Wikidata-Faktenfelder werden übernommen, aller
  Fließtext ist vom Generator selbst formuliert.
