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

**Bekannte Konsequenz:** Drei der so erzwungenen Hauptstädte (Kopenhagen, Amsterdam, Valletta)
haben — trotz erweiterter Sehenswürdigkeiten-Abfrage (Museum, Kirchengebäude, archäologische
Stätte, Denkmal) — **null** über `wdt:P131` direkt zuordenbare Sehenswürdigkeiten-Objekte in
Wikidata gefunden. Diese drei Dokumente bestehen fast ausschließlich aus dem Stadtporträt-Absatz
und dem Rang-Nachbarn-Vergleich; siehe `eval/corpus/city-landmarks/SOURCE.md` für die daraus
folgende Anhebung von `RANK_NEIGHBOR_RADIUS`.

### 4. Bevölkerungsdaten und Sortierung

Einwohnerzahl direkt aus GeoNames' `population`-Spalte (Feature-Klasse P, keine Wikidata-`P1082`-
Aussage mehr). Sortierung absteigend nach Einwohnerzahl, Gleichstand nach aufsteigender
GeoNames-ID.

### 5. Brücke zu Wikidata

`?city wdt:P1566 "<geonameid>"` — ausschließlich über diese Property, kein Namens-Matching.
Abdeckung: ca. 90 % der Top-Kandidaten. Bei mehreren zurückgegebenen QIDs für dieselbe GeoNames-ID
gewinnt die niedrigere (ältere, etabliertere) QID. **Dokumentierte Einzelausnahme:** Madrids
GeoNames-ID bindet an `Q116170766` („Stadt Madrid", eine neuere, sehenswürdigkeiten-leere
Split-Entität) statt an das etablierte `Q2807` („Madrid") — von Hand auf `Q2807` korrigiert, da
Letzteres die tatsächlichen Sehenswürdigkeiten-Daten trägt (dieselbe Art von Dubletten-Problem wie
bei der ursprünglichen P30-basierten Auswahl).

### 6. Sehenswürdigkeiten-Nebenbedingung und Mehr-Chunk-Sicherstellung

Reine Bevölkerungsrangliste reicht nicht: Städte ohne ausreichend dokumentierte Sehenswürdigkeiten
erfüllen den Domänenzweck nicht und erreichen oft nicht die Mehr-Chunk-Vorgabe (≥3 Chunks).
Auswahlregel: **Top 200 nach Einwohnerzahl unter den Kandidaten mit mindestens 2–3 dokumentierten
Sehenswürdigkeiten** (Schwelle iterativ ermittelt, siehe PR-Beschreibung), aus einem nach
Einwohnerzahl sortierten Kandidatenpool von rund 480 Städten. Sehenswürdigkeiten-Abfrage
(Abschnitt „Sehenswürdigkeiten" unten) deckt initial die Klassen Sehenswürdigkeit/Wahrzeichen/
Palast/Burg/Kathedrale ab; für Städte, die damit unter 3 blieben, wurde gezielt um Museum,
Kirchengebäude, archäologische Stätte und Denkmal erweitert (`wd:Q33506 wd:Q16970 wd:Q839954
wd:Q4989906`). Für eine einzelne Stadt (Reading, GB) blieb die Sehenswürdigkeiten-Zahl trotz
Erweiterung bei 2 — durch die nächstplatzierte, ausreichend dokumentierte Stadt ersetzt (Syzran,
RU, Rang nach Einwohnerzahl neu eingeordnet). **Ergebnis, mit dem echten `TokenTextSplitter`-Lauf
verifiziert:** alle 200 Dokumente erreichen mindestens 3 Chunks (siehe
`eval/corpus/city-landmarks/SOURCE.md`).

### Plausibilitätsnachweis

- Alle 200 Städte haben ≥ 100.000 Einwohner (kleinste: siehe `final-cities-200.json`, Rang 200).
- Stichprobe: London, Madrid, Kiew, Budapest, Warschau, Rom, Paris **enthalten**; Ankara,
  Jekaterinburg **nicht enthalten** — verifiziert.
- Länderverteilung der finalen 200 (Auszug, absteigend): RU 66, UA 28, PL 19, GB 16, DE 13,
  FR 11, BY 6, weitere 32 Länder mit 1–3 Städten. Die Dominanz Russlands und der Ukraine ist eine
  unmittelbare Folge der population-basierten Auswahl innerhalb der festgelegten
  Europa-Abgrenzung (viele Städte > 100.000 Einwohner im europäischen Teil Russlands/der Ukraine)
  — kein Artefakt der Auswahlregel, sondern ihr direktes, dokumentiertes Ergebnis.

## Sehenswürdigkeiten

Abfragemuster (Wikidata, `P131` direkt, keine Transitivität — Performance-Grund, siehe unten):

```sparql
SELECT ?city ?item WHERE {
  VALUES ?city { <QIDs, gebatcht> }
  ?item wdt:P131 ?city .
  VALUES ?class { wd:Q570116 wd:Q2319498 wd:Q16560 wd:Q23413 wd:Q2977 }
  ?item wdt:P31 ?class .
}
```

Basis-Klassen: `Q570116` Sehenswürdigkeit, `Q2319498` Wahrzeichen, `Q16560` Palast, `Q23413`
Burg/Schloss, `Q2977` Kathedrale. Erweiterung für unterversorgte Städte: `Q33506` Museum, `Q16970`
Kirchengebäude, `Q839954` archäologische Stätte, `Q4989906` Denkmal (nur dort verwendet, wo die
Basis-Klassen unter 3 Objekte lieferten — Denkmal/Monument allein liefert für viele Städte
überwiegend kleine Gedenktafeln ohne Sehenswürdigkeiten-Charakter, siehe frühere Testläufe).
Auswahl je Stadt: aufsteigende numerische QID, gedeckelt bei 12.

Detailfelder je Sehenswürdigkeit (Gründungsjahr, Eröffnungsjahr, Architekt/-in, Baustil, Höhe,
Koordinaten, Besucherzahl, Denkmalschutzstatus): dieselben `OPTIONAL`-Felder wie in der
ursprünglichen Fassung, unverändert.

## Bekannte Einschränkungen

- **Kein transitiver Klassenabschluss, kein zweistufiger `P131`-Bezug** — Performance-Grund
  (öffentlicher Wikidata Query Service bricht solche Abfragen zuverlässig nach 60 Sekunden ab).
  Die Sehenswürdigkeiten-Zahl je Stadt ist deshalb eine **Untergrenze**, nicht die
  Gesamtzahl der in Wikidata zu dieser Stadt vorhandenen Objekte.
- **GeoNames-Bevölkerungsdaten haben kein einheitliches Stichdatum** über alle 200 Städte hinweg
  (GeoNames pflegt je Ort das jeweils zuletzt eingetragene Datum, nicht global synchron).
- Für Sehenswürdigkeiten ohne deutsches **oder** englisches Label: Objekt ausgelassen (nicht mit
  QID-Platzhalter aufgefüllt). Für Städte ohne deutsches Label: Rückfall auf den GeoNames-Namen
  (betrifft 2 von 200 Städten).

## Lizenz und Zurechenbarkeit

Stadtliste (Name, Einwohnerzahl, Koordinaten, Land): GeoNames, CC-BY 4.0 — „Data © GeoNames.org,
CC-BY 4.0". Sehenswürdigkeiten- und Stadtfakten (Fläche, Höhenlage, Gründungsjahr,
Hauptstadt-Status): Wikidata, CC0-1.0. Kein Byte Text stammt aus Wikipedia oder Wikivoyage
(CC BY-SA, Share-alike — siehe Issue #234, Abschnitt „Quellen und Lizenzlage").
