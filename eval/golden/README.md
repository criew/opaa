# Golden Dataset: Domäne Comichelden

Das Golden Dataset für die Domäne `comic-characters` (Issue #226; Spezifikation in
[`docs/features/search-quality-evaluation.md`](../../docs/features/search-quality-evaluation.md),
Abschnitt „Golden Dataset"; [ADR-0008](../../docs/decisions/0008-search-quality-evaluation-harness.md)).
Es ist die Ground Truth für den Retrieval-Regressionstest aus #227.

## Dateien

| Datei | Inhalt |
|---|---|
| `comic-characters.json` | Das kuratierte Golden Dataset — 121 Fälle. Die von #227 gelesene Datei. |
| `comic-characters.candidates.json` | Alle 477 automatisch erzeugten Rohkandidaten, vor der Kuratierung. |
| `comic-characters.discarded.json` | Die 356 Kandidaten, die nicht in die Kuratierung übernommen wurden, je mit `reason`. |

Alle drei Dateien werden von [`eval/generator/generate_golden_dataset.py`](../generator/generate_golden_dataset.py)
erzeugt. Das Skript ist deterministisch (fixe Iterationsreihenfolge, keine Zeitstempel, keine
Zufallsquelle) — zwei Läufe gegen denselben Korpus erzeugen byte-identische Ausgaben, geprüft per
`diff` zweier aufeinanderfolgender Läufe.

## Lauf

```bash
cd eval/generator
python generate_golden_dataset.py
```

Voraussetzung: der Korpus unter `eval/corpus/comic-characters/` muss vorhanden sein (#225). Das
Skript liest ausschließlich das YAML-Frontmatter der dortigen Markdown-Dateien; es lädt nichts aus
dem Netz und benötigt keine Zusatzpakete (Python-Standardbibliothek genügt).

## Wie die Ground Truth entsteht

Jeder Fall wird aus dem strukturierten Frontmatter berechnet, nie aus einer LLM-Vermutung oder von
Hand geschätzt (siehe Spezifikation, Abschnitt „Ableitung aus dem Frontmatter"). Fünf Kategorien,
mit Vorlage und Berechnungsregel:

| Kategorie | Vorlage | Ground Truth | Beispiel |
|---|---|---|---|
| `attribute_lookup` | „What eye color does {name} have?" | genau die eine Datei | `comic-attr-001` |
| `entity_description` | Paraphrase über 3–4 unterscheidende Attribute, ohne den Namen zu nennen | genau die eine Datei — nur aufgenommen, wenn die Attributkombination im gesamten Korpus eindeutig ist | `comic-desc-001` |
| `multi_attribute_filter` | „Which {alignment}-aligned characters created by {creator} have the ability {ability}?" | alle passenden Dateien, Fenster [2, 15] | `comic-filter-001` |
| `numeric_range` | „Which characters have {a/an} {attribute} score {below/above} {n}?" | alle passenden Dateien, Fenster [2, 15] | `comic-range-001` |
| `crosslingual` | deutsche Übersetzung eines bereits validierten Falls aus einer der vier Kategorien oben | identisch zur Quelle | `comic-de-001` |

## Die drei verbindlichen Vorgaben aus dem Review von #225 (PR #249)

Issue #226 enthält zwei Kommentare mit Befunden aus dem Code-Review des Korpus-Generators, die für
diese Ableitung bindend sind. Beide sind im Generator-Code direkt an der jeweiligen Stelle
kommentiert, hier zusätzlich zusammengefasst:

### 1. `overall_score: null` bei den fünf Attributwerten ausschließen (verbindlich)

105 der 1.448 Dokumente sind unbewertete Figuren: Ihr `overall_score` ist `null`, und bei 104 von
ihnen enthält der generierte Fließtext wortwörtlich „scores 0 for intelligence, 0 for strength, …"
— dieser Text geht unverändert in den Vektorraum ein. Jede `numeric_range`-Anfrage auf einem der
fünf Attributwerte (`intelligence_score`, `strength_score`, `speed_score`, `durability_score`,
`combat_score`) filtert deshalb zusätzlich auf `overall_score is not null`
(`Entity.is_scored` / `BELOW_THRESHOLDS_BY_ATTRIBUTE`-Verarbeitung in
`generate_golden_dataset.py`). Verifiziert: Ohne diesen Ausschluss würden alle 105 unbewerteten
Figuren fälschlich in `Welche Figuren haben einen Intelligenzwert unter 50?` u. ä. auftauchen (siehe
Kuratierungs-Log unten, „Eigene inhaltliche Prüfung").

`overall_score` selbst wird unabhängig davon behandelt: Es liegt auf einer eigenen Skala (1–237,
plus 18-mal die Zeichenkette `"∞"`) und filtert nur auf sich selbst — `null`- und `"∞"`-Werte
scheiden dabei bereits durch die Typprüfung (`isinstance(..., int)`) aus, ohne einen separaten Test
zu benötigen.

### 2. Verunreinigte Quellspalten (`first_appearance`, `occupation`) ausschließen

`first_appearance` (Schwelle: 100 Zeichen) und `occupation` (Schwelle: 120 Zeichen) werden mit
einer Längenprüfung gegen die im Review genannten Verunreinigungsfälle abgesichert
(`Entity.first_appearance_is_plausible` / `occupation_is_plausible`). Verifiziert: Die beiden im
Review namentlich genannten Dokumente (`comic-0226_brainiac-5.md`, dessen `first_appearance` eine
Kraftfeld-Beschreibung ist; `comic-0498_gambit.md`, dessen `occupation` eine Adressliste ist)
erscheinen in keinem der 477 Rohkandidaten — durch die Plausibilitätsprüfung, nicht durch Zufall.

### 3. `overall_score` liegt auf einer anderen Skala als die fünf Attributwerte

`numeric_range`-Fälle auf `overall_score` verwenden eigene, empirisch ermittelte Schwellenwerte
(1–237-Skala, siehe `OVERALL_SCORE_BELOW_THRESHOLDS`/`OVERALL_SCORE_ABOVE_THRESHOLDS`), nie
dieselben Zahlen wie bei den 0–100-Attributwerten. Kein Fall vermischt beide Skalen in einem
Vergleich.

## Kuratierung

### Automatische Filter (im Generator, nicht Teil der manuellen Runde)

- Kontaminationsschwellen für `occupation`/`first_appearance` (siehe oben)
- `overall_score is not null` für alle Attributwert-Bereichsfragen (siehe oben)
- Fenster [2, 15] für `multi_attribute_filter` und `numeric_range`
- Eindeutigkeitsprüfung für `entity_description`: eine Attributkombination wird nur verwendet, wenn
  sie im gesamten Korpus zu genau einem Dokument führt
- Deduplizierung ist strukturell ausgeschlossen: jede Anfrage kombiniert Feld und Entität eindeutig,
  es gibt keine zwei Kandidaten mit identischer Frage

Nach diesen Filtern bleiben 477 Rohkandidaten — bei 1.448 Entitäten und einem Streufaktor von 7
(`SPREAD_STRIDE`, um nicht nur die alphabetisch ersten Entitäten zu ziehen) plus einer Obergrenze
pro Feld/Vorlage (`MAX_CANDIDATES_PER_FIELD` = 20), damit die Kandidatenliste überhaupt manuell
durchsehbar bleibt.

### Manuelle Runde

Die manuelle Runde (Spezifikation, Abschnitt „Kuratierung": „Silver → Gold") reduziert die 477
automatisch gültigen Kandidaten auf 121 kuratierte Fälle. Da nach den automatischen Filtern fast
jeder verbliebene Kandidat einzeln korrekt ist, bestand die manuelle Arbeit vor allem aus:

1. **Streuung statt Fülle**: pro Feld/Vorlage wurden 2–8 Fälle behalten, verteilt über
   unterschiedliche Entitäten und (bei `multi_attribute_filter`) unterschiedliche
   Alignment/Creator/Fähigkeit-Kombinationen, statt alle 20 pro Feld zu übernehmen.
2. **Zwei entdeckte Textqualitätsprobleme behoben, dann neu generiert** (nicht nur aus der
   Kuratierung entfernt — siehe „Im Generator behoben" unten).
3. **Gewichtung der `entity_description`-Vorlagen**: Die Vorlage, die `place_of_birth` und
   `occupation` kombiniert, erzeugt lesbar unruhigeren Text als die anderen beiden (das
   `occupation`-Feld enthält im Quelldatensatz uneinheitliche Groß-/Kleinschreibung, Kommas und
   Semikola, z. B. „adventurer, scientist; former crusader"). Sie ist deshalb mit 4 von 20 Fällen
   unterrepräsentiert statt mit den vollen 8, die die anderen beiden Vorlagen erhalten haben — nicht
   entfernt, weil ihre Ground Truth weiterhin korrekt ist und sie den Kombinationsraum erweitert,
   den keine der beiden anderen Vorlagen abdeckt (Geburtsort + Beruf statt Erschaffer/Team).
4. **`numeric_range` und `crosslingual` vollständig übernommen** (16 bzw. 34 von jeweils allen
   automatisch erzeugten Kandidaten): Beide Mengen sind bereits durch die Schwellenwertsuche bzw.
   die Übersetzung eines bereits kuratierten Falls klein und nicht-redundant; jede weitere Kürzung
   hätte nur Abdeckung gekostet, ohne Qualität zu gewinnen.

Die konkrete Auswahl ist als `CURATED_CASE_IDS` in `generate_golden_dataset.py` versioniert — sie
ist der Prüfstand für zukünftige Korpus-Änderungen: Verschieben sich IDs (weil sich der Korpus
ändert), bricht der Generator kontrolliert mit einer Fehlermeldung, statt still eine andere Auswahl
zu erzeugen.

### Im Generator behoben (nicht nur aus der Kuratierung entfernt)

Zwei Text-Bugs wurden während der Durchsicht der Rohkandidaten gefunden und im Generator-Code
selbst behoben, damit sie nicht nur für die 121 kuratierten Fälle, sondern für alle 477
Rohkandidaten korrigiert sind:

- **„has No Hair hair"**: Der `hair_color`-Wert „No Hair" ist ein Sentinel für „kahl", keine Farbe
  (dieselbe Konvention wie im Korpus-Generator, siehe dessen `BALD_HAIR_VALUES`). Die
  `entity_description`-Vorlage, die `hair_color` verwendet, überspringt solche Entitäten jetzt statt
  den unsinnigen Satz zu erzeugen.
- **„works as cEO"**: Die Vorlage senkt für die Mid-Satz-Einbettung den ersten Buchstaben von
  `occupation`. Bei Akronymen wie „CEO" erzeugte das „cEO". Behoben durch eine Prüfung, ob das erste
  Wort vollständig großgeschrieben ist (`_lowercase_first_word`).

Ein dritter, gravierenderer Bug betraf die deutschen `crosslingual`-Übersetzungen von
`multi_attribute_filter`/`numeric_range`-Fällen: Eine frühere Version extrahierte Creator- und
Fähigkeitsnamen durch Aufsplitten des menschenlesbaren Audit-Strings am Leerzeichen, was Werte mit
eigenem Leerzeichen am ersten Wort abschnitt („Dark Horse Comics" → „Dark", „Mind Control
Resistance" → „Mind"). Behoben durch ein strukturiertes `meta`-Feld auf jedem Kandidaten, das die
Übersetzung direkt aus den ursprünglichen Werten aufbaut statt sie aus Text zurückzugewinnen.

### Eigene inhaltliche Prüfung (statt nur die Erzeugung zu testen)

Sieben kuratierte Fälle wurden unabhängig vom Generator-Code gegen den Korpus nachgerechnet
(`grep`/eigenständiges Python-Skript, nicht `generate_golden_dataset.py`):

| Fall | Nachrechnung | Ergebnis |
|---|---|---|
| `comic-attr-004` (`Welche Augenfarbe hat Amygdala?`) | Frontmatter von `comic-0050_amygdala.md` gelesen | `eye_color: "Black"` — korrekt |
| `comic-filter-001` (gute Marvel-Figuren mit Reality Warping) | Alignment/Creator/Fähigkeit unabhängig aus allen 1.448 Dateien gefiltert | identische 7 Dateien wie im Golden-Fall |
| `comic-desc-054` (Castiel-Paraphrase über Geburtsort/Beruf/Augenfarbe) | Attributkombination unabhängig gegen alle 1.448 Dateien geprüft | genau 1 Treffer, `comic-0274_castiel.md` |
| `comic-range-001` (Intelligenzwert unter 35) | Fünf-Attribut- und `overall_score`-Felder unabhängig geparst, `overall_score is not null` angewendet | identische 2 Dateien wie im Golden-Fall |
| Kontrolle zur Vorgabe 1 | Ohne den `overall_score is not null`-Filter hätten 105 zusätzliche (unbewertete) Figuren `comic-range-001` u. ä. fälschlich getroffen | bestätigt die Notwendigkeit der Vorgabe, nicht nur ihre Umsetzung |
| Kontrolle zur Vorgabe 2 | `comic-0226_brainiac-5.md`/`comic-0498_gambit.md` explizit gegen die Plausibilitätsprüfung getestet | beide korrekt als unplausibel erkannt |
| Determinismus | Generator zweimal hintereinander laufen lassen, `diff` beider Ausgaben | byte-identisch |

Keiner der sieben Fälle musste korrigiert werden; die Nachrechnung bestätigt, dass die
Ground-Truth-Berechnung — nicht nur der Code, der sie aufruft — richtig ist.

## Kalibrierungshinweis für #227/#228

Der Korpus ist durch seine Satzschablonen messbar uniform: Der Reviewer von #225 hat einen
Jaccard-Median von 0,51 über ganze Dokumente und 0,38 über die reine Prosa gemessen. Das ist
gewollt (dieselbe Struktur macht Frontmatter-Ground-Truth erst möglich), staucht aber die
Score-Verteilung von Ähnlichkeitssuchen und macht Hit Rate/MRR unempfindlicher gegenüber echten
Regressionen als bei einem heterogenen Korpus. Schwellenwerte für die Retrieval-Regression in #227
und die CI-Gates in #228 sollten deshalb **gegen die tatsächliche, hier gemessene Score-Verteilung
dieses Datasets kalibriert werden, nicht gegen Erfahrungswerte aus anderen, heterogeneren
RAG-Korpora** — eine auf einem uniformen Korpus „normale" Baseline kann auf einem heterogenen Korpus
bereits eine Regression sein, und umgekehrt.

## Schema

```json
{
  "id": "comic-attr-001",
  "domain": "comic-characters",
  "query": "What eye color does Abin Sur have?",
  "expected_documents": ["comic-0008_abin-sur.md"],
  "category": "attribute_lookup",
  "difficulty": "easy",
  "language": "en",
  "type": "factual"
}
```

`expected_documents` referenziert Dateinamen relativ zu `eval/corpus/comic-characters/`. `domain`
entspricht dem Korpus-Verzeichnisnamen; ein Retrieval-Harness (#227), der mehrere Domänen
zusammenführt, kann Treffer über `domain` + `expected_documents` eindeutig zuordnen.
