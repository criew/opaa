# Eingefrorene Rohdaten: `city-landmarks` (Issue #234, PR #730 review)

**Neufassung nach Code-Review.** Die Städteliste wurde ursprünglich rein aus Wikidata (`P30`-
Kontinentzuordnung) abgeleitet; das ließ bekannte europäische Großstädte (London, Madrid, Kiew,
Budapest, Warschau, Köln u. a.) aus dem Kandidatensatz fallen und ließ 117 polnische Kleinstädte
bis 11.000 Einwohner in die „200 Großstädte" rutschen. Maintainer-Entscheidung: **GeoNames
`cities15000` als Primärquelle für die Städteliste**, gebrückt zu Wikidata über die Property
`P1566` (GeoNames-ID) — keine Namens-Zuordnung. Sehenswürdigkeiten bleiben durchgängig
Wikidata/CC0.

Abrufdatum aller Daten: **2026-08-21**.

## Dateien

| Datei | Inhalt | Lizenz |
|---|---|---|
| `geonames-cities15000.zip` | Rohdatei `cities15000.zip` von https://download.geonames.org/export/dump/cities15000.zip | CC-BY 4.0, Attribution „Data © GeoNames.org, CC-BY 4.0" (siehe https://download.geonames.org/export/dump/readme.txt) |
| `geonames-candidates-filtered.json` | Nach Länder-Whitelist, Transkontinental-Regel und `feature_code != PPLX` gefilterte Kandidaten (Feature-Klasse P), absteigend nach Einwohnerzahl | CC-BY 4.0 |
| `wikidata-geonames-bridge-raw.json` | GeoNames-ID → Wikidata-QID, ausschließlich über `P1566` | CC0-1.0 |
| `wikidata-city-facts-raw.json` | deutsches Stadt-/Länderlabel, Fläche, Höhenlage, Gründungsjahr, Hauptstadt-Status | CC0-1.0 |
| `wikidata-landmark-candidates-raw.json` | Stadt→Sehenswürdigkeit-Zuordnungen (alle Kandidaten) | CC0-1.0 |
| `wikidata-landmark-details-raw.json` | Detailfelder je ausgewählter Sehenswürdigkeit | CC0-1.0 |
| `final-cities-200.json` | **Vom Generator gelesene, endgültige Liste** der 200 Städte samt Rang, Einwohnerzahl, Land und zugeordneten Sehenswürdigkeiten-QIDs | — |

SHA-256-Werte stehen in `generate_city_landmarks_corpus.py` (`FROZEN_HASHES`); der Generator
bricht ab, falls eine dieser Dateien nachträglich verändert wird.

## Städteauswahl

### 1. Länder-Whitelist

~45 ISO-3166-Ländercodes: EU27 + GB, NO, IS, CH, UA, BY, MD, RS, BA, AL, MK, ME, XK, RU, TR sowie
die Mikrostaaten SM, MC, LI, AD, VA. **CY (Zypern): DRIN** (EU-Mitglied, Maintainer-Entscheidung).
**KZ, GE, AM, AZ: vollständig ausgeschlossen.**

### 2. Transkontinentale Staaten (Fallstrick, dokumentierte Ausnahmeregeln)

- **RU:** nur Städte mit Längengrad < 60,0° Ost (westlich des Ural) — Moskau, Sankt Petersburg,
  Perm sind enthalten, Jekaterinburg (≈60,6° O) nicht.
- **TR:** ausschließlich Istanbul, per expliziter Namens-Whitelist — ein reiner
  Längengrad-Schnitt trennt Bursa/İzmir nicht sauber vom asiatischen Festland ab.

### 3. Feature-Code-Whitelist (verschärft nach zweiter Review-Runde)

GeoNames-Feature-Klasse `P` („populated place"), **nur** die Codes `PPLC` (nationale Hauptstadt),
`PPLA`/`PPLA2`/`PPLA3`/`PPLA4` (Verwaltungssitz verschiedener Ebenen) und `PPL` (gewöhnlicher Ort).
**Explizit ausgeschlossen: `PPLX`** („section of populated place", z. B. Budapests „Pest"/„Buda",
Hamburgs Bezirke, Londons Boroughs, Moskauer Rajons, Pariser Arrondissements) — das sind
Stadtteile, keine eigenständigen Städte. **Fund im ersten Review:** Ohne diesen Ausschluss enthielt
der Kandidatensatz 94 PPLX-Einträge unter den 600 einwohnerstärksten Kandidaten, 6 davon bereits in
einer Zwischenfassung der 200er-Auswahl (u. a. „Pest" mit ca. 1,0 Mio. Einwohnern auf Rang 38,
„London Borough of Brent", „Hamburg-Mitte", die Moskauer Rajons „Marjino" und
„Wychino-Schulebino"). Die Positiv-Whitelist (statt eines bloßen `PPLX`-Ausschlusses) verschärft
das zusätzlich gegen jeden weiteren, noch unbekannten Nicht-Stadt-Feature-Code.

**Ergänzender 10-km-Dublettencheck:** Nach der Auswahl wird geprüft, ob zwei ausgewählte Einträge
näher als 10 km beieinanderliegen (Haversine-Distanz) — ein Indiz für dieselbe Stadt unter zwei
GeoNames-Einträgen. Im finalen Lauf griff das viermal (u. a. Sosnowiec zu nah an bereits
ausgewähltem Katowice, Mokotów zu nah an Warschau) — die jeweils kleinere Stadt wird übersprungen,
der nächste Kandidat rückt nach. Das ursprüngliche Budapest/Pest-Dublettenproblem selbst ist durch
den `PPLX`-Ausschluss oben bereits gelöst, bevor der 10-km-Check überhaupt greift.

### 3a. Pflicht-Aufnahme aller 27 EU-Hauptstädte (Maintainer-Entscheidung, zweite Review-Runde)

Alle 27 EU-Hauptstädte werden unabhängig von ihrer Einwohnerzahl aufgenommen; die übrigen 173
Plätze werden nach Einwohnerzahl unter den verbleibenden, ausreichend dokumentierten Kandidaten
aufgefüllt. Dadurch verdrängt: vier Hauptstädte liegen unter dem sonstigen Einwohnerzahl-Cutoff von
rund 191.000 — **Ljubljana** (272.220), **Nikosia** (200.452), **Luxemburg** (76.684) und
**Valletta** (6.794, im `cities15000`-Dump als `PPLC`-Ausnahme trotz Unterschreitens der
15.000-Einwohner-Schwelle enthalten, siehe GeoNames-Dokumentation). Skript-Check (`build_v2_pool.py`/
`final_select_v2.py`): alle 27 EU-Hauptstädte-Ländercodes sind in der finalen Liste vertreten,
gegen eine im Code fest hinterlegte 27er-Liste geprüft.

**Ursprünglicher Befund (überholt, siehe „Verifikationsrunde" unten):** Fünf der so erzwungenen
Hauptstädte (Kopenhagen, Amsterdam, Stockholm, Tallinn, Valletta) hatten trotz erweiterter
Sehenswürdigkeiten-Abfrage kaum oder keine über `wdt:P131` direkt zuordenbaren
Sehenswürdigkeiten-Objekte in Wikidata. Ursache war **kein** echter Datenmangel, sondern in allen
fünf Fällen eine Stadt-vs-Verwaltungseinheit-Dublette der `P1566`-Brücke — siehe unten.

### 4. Bevölkerungsdaten und Sortierung

Einwohnerzahl direkt aus GeoNames' `population`-Spalte (Feature-Klasse P, keine Wikidata-`P1082`-
Aussage mehr). Sortierung absteigend nach Einwohnerzahl, Gleichstand nach aufsteigender
GeoNames-ID.

### 5. Brücke zu Wikidata

`?city wdt:P1566 "<geonameid>"` — ausschließlich über diese Property, kein Namens-Matching.
Abdeckung: ca. 90 % der Top-Kandidaten. Bei mehreren zurückgegebenen QIDs für dieselbe GeoNames-ID
gewinnt die niedrigere (ältere, etabliertere) QID. **Dokumentierte QID-Ausnahmen** (dieselbe Art
von Dubletten-Problem, das schon bei der ursprünglichen P30-basierten Auswahl auftrat — Herleitung
und Belege siehe „Verifikationsrunde" unten):

| GeoNames-Stadt | P1566-Ziel (falsch) | Korrigiert auf | Grund |
|---|---|---|---|
| Madrid | `Q116170766` „Stadt Madrid" | `Q2807` „Madrid" | neuere, sehenswürdigkeiten-leere Split-Entität |
| Graz | `Q250880` „Innere Stadt" | `Q13298` „Graz" | GeoNames-ID zeigte auf einen Grazer Stadtbezirk statt auf die Stadt |
| Valletta | `Q20924973` (Örtlichkeit, `P131`-Kind von Valletta) | `Q23800` „Valletta" (Stadt) | `P1566` band an eine Örtlichkeit *innerhalb* Vallettas statt an die Stadt selbst |

### 6. Sehenswürdigkeiten-Nebenbedingung und Mehr-Chunk-Sicherstellung

Reine Bevölkerungsrangliste reicht nicht: Städte ohne ausreichend dokumentierte Sehenswürdigkeiten
erfüllen den Domänenzweck nicht und erreichen oft nicht die Mehr-Chunk-Vorgabe (≥3 Chunks).
Auswahlregel: **Top 200 nach Einwohnerzahl unter den Kandidaten mit mindestens 2–3 dokumentierten
Sehenswürdigkeiten** (Schwelle iterativ ermittelt, siehe PR-Beschreibung), aus einem nach
Einwohnerzahl sortierten Kandidatenpool von rund 480 Städten. Sehenswürdigkeiten-Abfrage
(Abschnitt „Sehenswürdigkeiten" unten) deckt initial die Klassen Sehenswürdigkeit/Wahrzeichen/
Palast/Burg/Kathedrale ab; für Städte, die damit unter 3 blieben, wurde gezielt um Museum,
Kirchengebäude, archäologische Stätte und Denkmal erweitert (`wd:Q33506 wd:Q16970 wd:Q839954
wd:Q4989906`). **Ergebnis, mit dem echten `TokenTextSplitter`-Lauf verifiziert:** alle 200
Dokumente erreichen mindestens 3 Chunks bei `RANK_NEIGHBOR_RADIUS=2` (siehe
`eval/corpus/city-landmarks/SOURCE.md` und Abschnitt „Verifikationsrunde" unten).

### Plausibilitätsnachweis

- Alle Städte bis auf zwei haben ≥ 100.000 Einwohner (Ausnahmen Luxemburg 76.684 und Valletta
  6.794, beide durch die EU-Hauptstadt-Pflichtregel oben).
- Stichprobe: London, Madrid, Kiew, Budapest, Warschau, Rom, Paris **enthalten**; Ankara,
  Jekaterinburg **nicht enthalten** — verifiziert.
- Alle 27 EU-Hauptstädte enthalten (Skript-Check gegen fest hinterlegte 27er-Liste).
- Länderverteilung der finalen 200 (absteigend): RU 64, UA 27, GB 16, PL 16, DE 13, FR 11, BY 6,
  RO 3, AT 3, BG 3, ES 2, IT 2, RS 2, CZ 2, NL 2, plus 24 weitere Länder mit 1 Stadt — 39 Länder
  insgesamt. Die Dominanz Russlands und der Ukraine ist eine unmittelbare Folge der
  population-basierten Auswahl innerhalb der festgelegten Europa-Abgrenzung, kein Artefakt der
  Auswahlregel.

## Sehenswürdigkeiten

**Zweistufige Abfrage (Verifikationsrunde, siehe unten für den Anlass):** Stufe 1 (günstig) prüft
je Stadt nur `P131` direkt und `P276` (Lage); nur wenn das unter 5 Objekte liefert, greift Stufe 2
(teurer) zusätzlich mit dem zweistufigen `P131`-Bezug (Objekt → Stadtbezirk/Gemeindeteil → Stadt).
Das hält die WDQS-Last gering (die teure Zweihopf-Variante lief nur für eine Minderheit der 200
Städte), ohne strukturell dünne Städte zu benachteiligen.

```sparql
SELECT ?item ?sl WHERE {
  { ?item wdt:P131 wd:<Stadt-QID> . } UNION { ?item wdt:P276 wd:<Stadt-QID> . }
  # nur falls Stufe 1 < 5 Treffer liefert, zusätzlich:
  # UNION { ?item wdt:P131 ?district . ?district wdt:P131 wd:<Stadt-QID> . }
  VALUES ?class { wd:Q570116 wd:Q2319498 wd:Q16560 wd:Q23413 wd:Q2977 wd:Q33506 wd:Q16970 wd:Q839954 wd:Q4989906 }
  ?item wdt:P31 ?class .
  OPTIONAL { ?item wikibase:sitelinks ?sitelinks }
  BIND(COALESCE(?sitelinks, 0) AS ?sl)
}
ORDER BY DESC(?sl) ASC(?item)
LIMIT 15
```

Klassen: `Q570116` Sehenswürdigkeit, `Q2319498` Wahrzeichen, `Q16560` Palast, `Q23413`
Burg/Schloss, `Q2977` Kathedrale, `Q33506` Museum, `Q16970` Kirchengebäude, `Q839954`
archäologische Stätte, `Q4989906` Denkmal — durchgängig alle neun Klassen, nicht mehr nur bei
Bedarf erweitert (Verifikationsrunde: die generalisierte Verbreiterung sollte auch andere Städte
inhaltlich reicher machen, nicht nur die zunächst betroffenen fünf Hauptstädte). **Deckelung je
Stadt: 15 Sehenswürdigkeiten, sortiert nach Sitelink-Zahl absteigend, Tiebreak aufsteigende
numerische QID** (Sitelink-Zahl als Proxy für Bekanntheit/Relevanz — deterministisch, weil die
Sitelink-Zahl zusammen mit der eingefrorenen Rohdatei fixiert ist, nicht zur Laufzeit neu
abgefragt). Bei gleichem Namen zweier ausgewählter Objekte (kommt vereinzelt vor, wenn die
verbreiterte Abfrage zwei eng verwandte Entitäten mit identischem Label liefert): nur das erste
(höchste Sitelink-Zahl) wird gerendert, siehe `build_cities()` in
`generate_city_landmarks_corpus.py`.

Detailfelder je Sehenswürdigkeit (Gründungsjahr, Eröffnungsjahr, Architekt/-in, Baustil, Höhe,
Koordinaten, Besucherzahl, Denkmalschutzstatus): unverändert dieselben `OPTIONAL`-Felder.

## Bekannte Einschränkungen

- **GeoNames-Bevölkerungsdaten haben kein einheitliches Stichdatum** über alle 200 Städte hinweg
  (GeoNames pflegt je Ort das jeweils zuletzt eingetragene Datum, nicht global synchron).
- Für Sehenswürdigkeiten ohne deutsches **oder** englisches Label: Objekt ausgelassen (nicht mit
  QID-Platzhalter aufgefüllt). Für Städte ohne deutsches Label: Rückfall auf den GeoNames-Namen
  (betrifft 2 von 200 Städten).
- **P1376 „Hauptstadt von" ist in Wikidata sehr uneinheitlich belegt** (historische Reiche,
  Verwaltungsuntereinheiten, teils sogar selbstreferenziell) — der Generator rendert eine
  Hauptstadt-Aussage deshalb nur, wenn sie exakt mit dem Land der Stadt übereinstimmt (siehe
  `build_cities()`); alle anderen `P1376`-Aussagen werden verworfen, nicht als Fließtext gerendert.

## Verifikationsrunde (PR #730, dritte Review-Runde)

Ein Verifikations-Review deckte auf, dass `RANK_NEIGHBOR_RADIUS` in der zweiten Runde still auf
40 belassen worden war, obwohl der Code-Kommentar dort weiterhin „immediate neighbors only"
(Radius klein) behauptete — eine nicht gemeldete Abweichung von der eigentlichen Anweisung
(„radius 36→2"). Korrigiert:

1. **`RANK_NEIGHBOR_RADIUS` auf 2 gesetzt.** Damit fielen zunächst 10 Dokumente unter die
   3-Chunk-Vorgabe, darunter 5 EU-Hauptstädte (Kopenhagen, Amsterdam, Stockholm, Tallinn,
   Valletta) — gemeldet, bevor irgendetwas an der Auswahl geändert wurde.
2. **Ursachenanalyse statt Auswahl-Umbau:** Für Kopenhagen, Tallinn und Valletta war die dünne
   Sehenswürdigkeiten-Zahl keine echte Datenlücke, sondern eine `P1566`-Stadt-vs-Verwaltungseinheit-
   Dublette (siehe Tabelle oben) — bekannte Landmarken wie die Kleine Meerjungfrau (`P131` →
   `Q504125` „Kommune Kopenhagen") oder St. John's Co-Cathedral (`P131` → `Q23800`, nicht das
   ursprünglich gebrückte `Q20924973`) zeigen auf eine andere Entität als die von `P1566`
   gelieferte. Für Kopenhagen/Tallinn wurde die jeweilige Verwaltungseinheit als zusätzliches
   akzeptiertes `P131`-Ziel in die Sehenswürdigkeiten-Abfrage aufgenommen (Q1748 zusätzlich
   Q504125; Q1770 zusätzlich Q4450503); für Valletta wurde die `P1566`-Bindung selbst auf die
   korrekte Stadtentität `Q23800` korrigiert (siehe QID-Ausnahmetabelle oben). Amsterdam und
   Stockholm lösten sich bereits durch die generalisierte Abfrage-Verbreiterung (Punkt „Sehens-
   würdigkeiten" oben) ohne Sonderbehandlung. Eine handkuratierte Ausnahmeliste (Option 2 aus der
   Entscheidung) war für keine der drei Städte nötig.
3. **Reading (GB) fliegt raus, kein EU-Hauptstadt-Sonderfall:** blieb auch mit verbreiterter
   Abfrage bei 3 Sehenswürdigkeiten-Kandidaten und 2 Chunks. Ersetzt durch **Brighton** (GB,
   nächstplatzierter, ausreichend dokumentierter Kandidat unterhalb von Readings
   Einwohnerzahl-Rang 318.014 — mehrere höher gerankte Kandidaten wie Constanța/RO, Kingston upon
   Hull/GB, Münster/DE hatten entweder keinen `P1566`-Treffer oder zu wenige Sehenswürdigkeiten;
   Brighton war der erste Kandidat mit deutschem Label und ≥ 5 Sehenswürdigkeiten-Kandidaten,
   14 nach Deckelung), gleicher Rangplatz (131).
4. **`country_de`-Reparatur:** 28 Städte hatten `country_de: null` (weitere 3 hatten durch eine
   Encoding-Verkettung verstümmelte Werte), weil die ursprüngliche Wikidata-Länderlabel-Abfrage
   nicht für alle 200 Städte griff — `country_code` (GeoNames, ISO) war dagegen immer vollständig.
   `country_de` wird jetzt für alle 200 Städte deterministisch aus `country_code` über eine feste
   ISO→deutscher-Name-Tabelle gesetzt (nicht mehr aus der Wikidata-Abfrage übernommen).
5. **P1566-Plausibilitätsabgleich über alle 200 Städte:** GeoNames-`asciiname` gegen `name_de`
   normalisiert verglichen (Akzente/Transliteration entfernt, `difflib.SequenceMatcher`-Ähnlichkeit).
   Nur Graz fiel als echter Fehltreffer auf (Ähnlichkeit 0,27); alle 26 weiteren Abweichungen unter
   0,75 sind erwartete Exonyme/Transliterationen (Moskau/Moscow, Warschau/Warsaw, Lemberg/Lviv,
   Breslau/Wroclaw, Nizza/Nice, Krakau/Krakow, Danzig/Gdansk usw.) — keine weiteren Dubletten.
6. **`maxChunksPerDocument`** in `EvalDomainConfig.CITY_LANDMARKS` sinkt entsprechend (siehe
   `eval/corpus/city-landmarks/SOURCE.md` für den gemessenen Wert nach dieser Runde).

## Lizenz und Zurechenbarkeit

Stadtliste (Name, Einwohnerzahl, Koordinaten, Land): GeoNames, CC-BY 4.0 — „Data © GeoNames.org,
CC-BY 4.0". Sehenswürdigkeiten- und Stadtfakten (Fläche, Höhenlage, Gründungsjahr,
Hauptstadt-Status): Wikidata, CC0-1.0. Kein Byte Text stammt aus Wikipedia oder Wikivoyage
(CC BY-SA, Share-alike — siehe Issue #234, Abschnitt „Quellen und Lizenzlage").
