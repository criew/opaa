# Eingefrorene Wikidata-Rohdaten: `city-landmarks` (Issue #234)

Alle Dateien in diesem Verzeichnis sind ein **eingefrorener Schnappschuss** von
[Wikidata](https://www.wikidata.org)-SPARQL-Abfragen (CC0-1.0, siehe
[Wikidata:Licensing](https://www.wikidata.org/wiki/Wikidata:Licensing) und
[SPARQL query service/Copyright](https://www.wikidata.org/wiki/Wikidata:SPARQL_query_service/Copyright)).
Der Generator (`eval/generator/generate_city_landmarks_corpus.py`) liest **ausschließlich** diese
Dateien, nie den Live-Endpunkt — siehe Issue #234, Abschnitt „Städteauswahl: deterministisch und
eingefroren".

Abrufdatum aller Abfragen: **2026-08-21**. Endpunkt: `https://query.wikidata.org/sparql`.

## Dateien

| Datei | Inhalt | SHA-256 |
|---|---|---|
| `wikidata-cities-raw.json` | Rohergebnis der Städteabfrage (784 Kandidaten, s.u.) | `a6ee7a5f22fb9e5ae1ad7e922947827a3f802bc6b8dac6cc7971172c4a382d99` |
| `wikidata-city-facts-raw.json` | Fläche, Höhenlage, Gründungsjahr, Hauptstadt-Status für die finalen 200 Städte | `bda61d994f07d1024bef2a1cc481f5fdf93d6a0569e2e415d0474d1fe51f387c` |
| `wikidata-landmark-candidates-raw.json` | Stadt→Sehenswürdigkeit-Zuordnungen (Kandidaten, alle Ränge) | `5db5249c7558b0419879fc4c49ab035c8a8c1a6441d798db62358a0f88e71f97` |
| `wikidata-landmark-details-raw.json` | Detailfelder je ausgewählter Sehenswürdigkeit (892 Objekte) | `6ea3d9d374dbec6ff2329143cbc98e02c7c62d4ac9b5072f26dd9bcf30962267` |
| `wikidata-cities-200.json` | **Vom Generator gelesene, endgültige Liste** der 200 Städte samt Rang, Einwohnerzahl und zugeordneten Sehenswürdigkeiten-QIDs | `94bbbeffcbe396259e2f8fac29bb2779b4b096d997d5cbf9ff9bffb6b06bfc39` |

Die SHA-256-Werte sind zusätzlich in `generate_city_landmarks_corpus.py` (`FROZEN_HASHES`) hinterlegt
— der Generator bricht ab, falls eine dieser Dateien nachträglich verändert wird.

## Städteauswahl (Abfrage 1)

### Klassenmenge (Fallstrick „uneinheitliche P31-Belegung")

Europäische Großstädte sind in Wikidata uneinheitlich typisiert („Stadt", „Großstadt", „Metropole",
„Megastadt", „Hauptstadt" u. Ä. nebeneinander, nicht konsistent vergeben — siehe Issue #234).
Verwendete, explizit dokumentierte Klassenmenge (`wdt:P31`, **nicht** transitiv über `wdt:P279*`,
aus Performance-Gründen: eine transitive Abfrage über den vollständigen Städte-Ontologiebaum
überschreitet zuverlässig das 60-Sekunden-Zeitbudget des öffentlichen Wikidata Query Service):

- `wd:Q515` — Stadt (city)
- `wd:Q1549591` — Großstadt (big city)
- `wd:Q200250` — Metropole (metropolis)
- `wd:Q174844` — Megastadt (megacity)
- `wd:Q5119` — Hauptstadt (capital city)

### Europa-Abgrenzung (Fallstrick „transkontinentale Staaten")

`?city wdt:P30 wd:Q46` — die **Kontinentzuordnung der Stadt selbst**, nicht des Staates (Maintainer-
Entscheidung, Issue #234, Kommentar vom 21.08.2026). Da Wikidata für Städte auf beiden Seiten einer
Kontinentgrenze (z. B. Istanbul: `P30` sowohl `Q46` Europa als auch `Q48` Asien) mehrere `P30`-Werte
parallel führt, genügt eine `EXISTS`-Prüfung auf `Q46` — Istanbul, Moskau und Sankt Petersburg sind
dadurch erwartungsgemäß enthalten, Ankara und Jekaterinburg (nur `P30 = Q48`/`Q46` fehlt) nicht.
Stichprobenartig verifiziert (siehe Abfrageprotokoll in der PR-Beschreibung).

### Einwohnerzahl (Fallstrick „Verwaltungsgrenze vs. Ballungsraum")

Maintainer-Entscheidung (Issue #234, Kommentar vom 21.08.2026): Rangfolge nach
**Ballungsraum-/Metropolregion-Einwohnerzahl mit dokumentiertem Rückfall auf die Gemeindezahl**.

**Tatsächliches Ergebnis dieser Abfrage:** Für keine der 784 Kandidatenstädte trägt die
`wdt:P1082`-Aussage der Stadt selbst eine Qualifikator-Kennzeichnung
(`pq:P518` = `wd:Q1907114` „Metropolregion" oder `wd:Q702492` „Ballungsraum"). Das ist die von der
Maintainer-Entscheidung selbst antizipierte Konsequenz („Die Wikidata-Datenlage ist dort
unschärfer"). Der Rückfall auf die Gemeindezahl greift damit **für alle 200 ausgewählten Städte**,
nicht nur vereinzelt — kein stiller Sonderfall, sondern die durchgängige, dokumentierte Regel dieser
Abfrage:

```sparql
SELECT ?city ?cityLabel ?countryLabel ?pop ?date ?kind WHERE {
  VALUES ?cityClass { wd:Q515 wd:Q1549591 wd:Q200250 wd:Q174844 wd:Q5119 }
  ?city wdt:P31 ?cityClass .
  ?city wdt:P30 wd:Q46 .
  OPTIONAL { ?city wdt:P17 ?country . }
  ?city p:P1082 ?stmt .
  ?stmt ps:P1082 ?pop .
  OPTIONAL { ?stmt pq:P585 ?date . }
  OPTIONAL { ?stmt pq:P518 ?kind . }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "de,en". }
}
```

Ergebnis: 784 Städte mit mindestens einer `P1082`-Aussage (`wikidata-cities-raw.json`, 5.599
Zeilen — mehrere Zeitpunkte je Stadt sind normal, siehe unten).

### Auswahlregel je Stadt

Je Stadt wird aus allen `P1082`-Zeilen deterministisch **ein** Wert gewählt:

1. Falls mindestens eine Zeile mit Ballungsraum-/Metropolregion-Qualifikator existiert (siehe oben)
   — im aktuellen Datenstand: nie —, wird unter diesen Zeilen die mit dem spätesten `P585`-Zeitpunkt
   gewählt.
2. Sonst (im aktuellen Datenstand: immer) wird unter **allen** Zeilen die mit dem spätesten
   `P585`-Zeitpunkt gewählt (fehlender Zeitpunkt sortiert niedriger als jeder vorhandene); bei
   gleichem — oder fehlendem — Zeitpunkt gewinnt der größere Einwohnerwert.

Anschließend: absteigende Sortierung nach diesem Wert, Gleichstand nach aufsteigender QID (**nie**
nach Label, da Labels sich ändern können — Issue #234, Abschnitt „Sortierung und Auswahl").

### Nebenbedingung „mindestens eine dokumentierte Sehenswürdigkeit"

Eine reine Bevölkerungsrangliste ließ 49 der ursprünglichen Top-200-Kandidaten ohne einen einzigen
Sehenswürdigkeiten-Treffer aus Abfrage 2 (kleinere Städte sind in Wikidata für Sehenswürdigkeiten mit
`wdt:P131`-Direktbezug deutlich dünner erschlossen als für ihre eigene Einwohnerzahl). Damit die
Domäne ihren Zweck erfüllt — Dokumente über Sehenswürdigkeiten, nicht bloße Stadtporträts —, gilt
eine zusätzliche, ebenfalls deterministische Nebenbedingung: Unter den nach Einwohnerzahl
absteigend sortierten Kandidaten (Ränge 1–400 dieser Abfrage) werden nur solche mit **mindestens
einer** in Abfrage 2 gefundenen Sehenswürdigkeit berücksichtigt; die ersten 200 davon bilden die
finale Liste. Das hat 49 der ursprünglichen Top-200-Kandidaten (nach reiner Einwohnerzahl) durch die
nächstplatzierten, sehenswürdigkeitenhaltigen Städte ersetzt — dieselbe Sortierregel, nur mit dieser
einen zusätzlichen Bedingung. Die kleinste in der finalen Liste enthaltene Stadt hat 10.974
Einwohner (Koronowo, Polen, Rang 200).

Ebenfalls ausgeschlossen: das einzige Element der Top-200-Kandidaten ohne deutsches Label
(`Q681893`, Errenteria/Spanien, nur englisches Label in diesem Datenstand) — Issue #234, technischer
Hinweis „Rückfall: Objekt auslassen, statt englisches Label in deutschen Text zu mischen".

## Sehenswürdigkeiten (Abfrage 2)

### Klassenmenge und Ortsbezug

```sparql
SELECT ?city ?item WHERE {
  VALUES ?city { <bis zu 25 QIDs je Teilabfrage, s.u.> }
  ?item wdt:P131 ?city .
  VALUES ?class { wd:Q570116 wd:Q2319498 wd:Q16560 wd:Q23413 wd:Q2977 }
  ?item wdt:P31 ?class .
}
```

- `wd:Q570116` — Sehenswürdigkeit (tourist attraction)
- `wd:Q2319498` — Wahrzeichen (landmark)
- `wd:Q16560` — Palast (palace)
- `wd:Q23413` — Burg/Schloss (castle)
- `wd:Q2977` — Kathedrale (cathedral)

Objektbezug ausschließlich über `wdt:P131` (administrative Lage), direkt auf die Stadt — **nicht**
transitiv und **nicht** über `wdt:P276` (Ort), aus demselben Performance-Grund wie bei der
Klassenmenge oben (siehe Abschnitt „Bekannte Einschränkungen"). Breitere Klassen (Museum `Q33506`,
Kirchengebäude `Q16970`, archäologische Stätte `Q839954`, Denkmal `Q4989906`) wurden getestet und
verworfen: Sie überschreiten entweder zuverlässig das Zeitbudget des öffentlichen Endpunkts oder
liefern (Denkmal) überwiegend kleine Gedenktafeln/Statuen ohne den für diese Domäne intendierten
Sehenswürdigkeiten-Charakter.

**Auswahlregel je Stadt** (Issue #234: „die N nach P1174 bestbelegten Objekte, Gleichstand nach
QID"): `P1174` (Besucher pro Jahr) ist in der Praxis nur für einen kleinen Teil der Kandidaten belegt
(siehe `wikidata-landmark-candidates-raw.json` — nur 52 von 200 Städten haben mindestens ein Objekt
mit `P1174`). Die tatsächlich verwendete, davon abweichende und hier explizit dokumentierte Regel:
Unter den Kandidatenobjekten einer Stadt werden die ersten 12 in aufsteigender numerischer
QID-Reihenfolge übernommen (deterministisch, keine `P1174`-Abhängigkeit, da dieses Feld für die
große Mehrheit der Städte keine Auswahl ermöglichen würde). `P1174` wird, wo vorhanden, weiterhin als
Fließtextfeld übernommen (siehe `wikidata-landmark-details-raw.json`).

### Detailfelder (dritte Abfrage, nur für die ausgewählten ≤12 Objekte je Stadt)

```sparql
SELECT ?item ?itemLabel ?inception ?opening ?architectLabel ?styleLabel ?height ?coord ?visitors
       ?heritageLabel WHERE {
  VALUES ?item { <ausgewählte QIDs, batched> }
  OPTIONAL { ?item wdt:P571 ?inception }
  OPTIONAL { ?item wdt:P1619 ?opening }
  OPTIONAL { ?item wdt:P84 ?architect }
  OPTIONAL { ?item wdt:P149 ?style }
  OPTIONAL { ?item wdt:P2048 ?height }
  OPTIONAL { ?item wdt:P625 ?coord }
  OPTIONAL { ?item wdt:P1174 ?visitors }
  OPTIONAL { ?item wdt:P1435 ?heritage }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "de,en". }
}
```

Abdeckungsquote je Feld über die 892 ausgewählten Objekte (aus `wikidata-landmark-details-raw.json`):
Name (deutsch oder englisch, ansonsten verworfen — s. u.) 100 %, Koordinaten ca. 85 %, Gründungsjahr
ca. 45 %, Architekt/-in ca. 15 %, Baustil ca. 12 %, Denkmalschutzstatus ca. 10 %, Höhe ca. 5 %,
Besucherzahl ca. 8 %. Objekte ohne deutsches **oder** englisches Label werden verworfen (Issue #234,
technischer Hinweis).

### Stadt-Zusatzfelder

```sparql
SELECT ?city ?area ?elevation ?inception ?capitalOf ?capitalOfLabel WHERE {
  VALUES ?city { <200 finale QIDs> }
  OPTIONAL { ?city wdt:P2046 ?area }
  OPTIONAL { ?city wdt:P2044 ?elevation }
  OPTIONAL { ?city wdt:P571 ?inception }
  OPTIONAL { ?city wdt:P1376 ?capitalOf }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "de,en". }
}
```

## Bekannte Einschränkungen dieser eingefrorenen Abfragen

- **Kein transitiver Klassenabschluss (`wdt:P279*`).** Der öffentliche Wikidata Query Service
  bricht solche Abfragen über die hier verwendeten Klassen zuverlässig nach 60 Sekunden ab
  (wiederholt beobachtet während der Erstellung dieses Datensatzes). Die Klassenmenge ist deshalb
  explizit aufgezählt statt über Vererbung abgeleitet — mit der Konsequenz, dass Unterklassen, die
  nicht direkt in der Liste stehen, nicht erfasst werden.
- **Nur `wdt:P131` (administrative Lage direkt auf die Stadt), kein zweistufiger Bezug über
  Stadtbezirke und kein `wdt:P276`.** Aus demselben Performance-Grund. Das senkt die Trefferzahl in
  Städten, deren Sehenswürdigkeiten in Wikidata einem Stadtbezirk statt der Stadt selbst zugeordnet
  sind.
- **Batching wegen Zeit-/Antwortgrößenlimits.** Die Städteabfragen liefen in Gruppen von 20–25
  QIDs (teils weiter unterteilt bei Zeitüberschreitung); die Detailabfragen in Gruppen von rund 100
  QIDs. Die Ergebnisse sind vor dem Schreiben der finalen Dateien dedupliziert und deterministisch
  sortiert zusammengeführt (siehe `generate_city_landmarks_corpus.py`).
- **Zusammen genommen bedeuten diese drei Einschränkungen:** Die Zahl der pro Stadt gefundenen
  Sehenswürdigkeiten ist eine **Untergrenze** dessen, was in Wikidata tatsächlich zu jeder Stadt
  vorhanden ist, nicht die Gesamtzahl. Für die 200 ausgewählten Städte genügt sie durchgängig für
  mindestens ein Objekt (Auswahlbedingung oben); für die Mehrzahl der kleineren Städte bleibt sie im
  einstelligen Bereich.

## Lizenz und Zurechenbarkeit

Alle Werte in diesem Verzeichnis sind strukturierte Wikidata-Fakten (CC0-1.0) — keine
Textübernahme aus Wikipedia oder Wikivoyage (CC BY-SA, siehe Issue #234, Abschnitt „Quellen und
Lizenzlage"). Der generierte Fließtext (`eval/corpus/city-landmarks/*.md`) wird ausschließlich vom
Generator aus diesen strukturierten Feldern formuliert.
