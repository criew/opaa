# Quelle: Domäne Sehenswürdigkeiten in europäischen Großstädten

| | |
|---|---|
| **Quelle** | GeoNames `cities15000` (Städteliste) + [Wikidata](https://www.wikidata.org) (Sehenswürdigkeiten, Stadtfakten) |
| **Lizenz** | CC-BY 4.0 (GeoNames, Attribution „Data © GeoNames.org, CC-BY 4.0") + CC0-1.0 (Wikidata) |
| **Abrufdatum** | 2026-08-21 |
| **Eingefrorene Rohdaten** | [`../../generator/frozen/`](../../generator/frozen/), siehe dort `SOURCE.md` für die vollständigen Abfragen, Auswahlregeln und Fallstrick-Entscheidungen (Issue #234, PR #730 review) |
| **Dokumente** | 200 (`city-0001_*.md` … `city-0200_*.md`), sortiert nach Rang (Einwohnerzahl, absteigend) |
| **Sampling** | Kein Sampling im statistischen Sinn — deterministische Auswahlregel (Einwohnerzahl unter Kandidaten mit ausreichend dokumentierten Sehenswürdigkeiten, GeoNames-Feature-Code `PPLX` ausgeschlossen), siehe `../../generator/frozen/SOURCE.md` |

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
| Minimum | 8.797 |
| Median | 27.301 |
| Maximum | 35.547 |
| Gesamtgröße | ca. 4,92 MB |

**Abweichung von der ursprünglichen Abschätzung (1,2–2,4 MB, Issue #234 „Prüfpunkt: Korpus-Ablage"):**
Die tatsächliche Größe liegt darüber, weil die generalisierte Sehenswürdigkeiten-Abfrage
(Verifikationsrunde, PR #730 dritte Review-Runde — zweistufiger `P131`-Bezug, `P276`, neun statt
fünf Klassen, Deckelung 15 statt 12 je Stadt) für die meisten Städte deutlich mehr dokumentierte
Sehenswürdigkeiten liefert als jede vorherige Fassung. Der Gesamtumfang bleibt mit rund 4,92 MB
(zusammen mit den rund 1,9 MB von `comic-characters`: rund 6,8 MB) weiterhin deutlich unter der
25-MB-Prüfschwelle aus ADR-0011.

**Chunk-Zahl-Verteilung** (echter `TokenTextSplitter`-Lauf, `chunkSize=1000`, `chunkOverlap=100` —
Docker-freier Trockenlauf über `io.opaa.eval.CityLandmarksChunkSizeDryRunTest`, siehe PR-Beschreibung
für den vollständigen `checkRetrievalBaseline`-Nachweis): Minimum 3, Median 8, Maximum 11 Chunks je
Dokument — die Domänen-Vorgabe „mindestens 3 Chunks je Dokument" (#721/#234) ist für alle 200
Dokumente erfüllt (0 Verletzungen).

**`RANK_NEIGHBOR_RADIUS = 2`** (korrigiert in der Verifikationsrunde, PR #730 dritte Review-Runde —
der Wert war in der zweiten Runde fälschlich bei 40 belassen worden, obwohl der Code-Kommentar
weiterhin „immediate neighbors only" behauptete; siehe `../../generator/frozen/SOURCE.md`,
Abschnitt „Verifikationsrunde"). Jede Stadt vergleicht sich nur noch mit maximal vier
Rang-Nachbarn (zwei davor, zwei danach; am Rand der Rangliste — Rang 1/2 bzw. 199/200 — auf die
jeweils andere Seite ausgedehnt, damit auch dort ein voller Nachbar-Umfang entsteht). Die
Mehr-Chunk-Vorgabe wird jetzt durchgängig über die reichhaltigere Sehenswürdigkeiten-Abfrage erreicht,
nicht mehr über einen großzügigen Rang-Vergleichs-Abschnitt — der Boilerplate-Anteil (feste
Vorlagensätze: Rang-Einordnung, Rang-Nachbarn-Vergleich, wiederkehrende Korpus-Hinweissätze) sank
dadurch von einem deutlich zweistelligen Anteil bei Radius 40 auf **rund 9 % der Korpusgröße**
(gemessen über alle 200 Dokumente).

## Bekannte Eigenschaften und Grenzen dieses Korpus

- **Sehenswürdigkeiten-Dichte ist ungleich verteilt, aber nach der Verifikationsrunde deutlich
  gleichmäßiger.** Median 15 (Deckelung im Generator), Minimum 4 dokumentierte
  Sehenswürdigkeiten-Kandidaten je Stadt — siehe `../../generator/frozen/SOURCE.md`, Abschnitt
  „Verifikationsrunde" für die zweistufige, generalisierte Abfrage, die diese Verteilung gegenüber
  früheren Fassungen (Minimum 0 bei mehreren EU-Hauptstädten) verbessert hat.
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
