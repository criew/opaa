# Quelle: Domäne Antwortqualität

| | |
|---|---|
| **Quelle** | vollständig synthetisch, kein Rohdaten-Snapshot einer Fremdquelle |
| **Lizenz** | CC0-1.0 |
| **Generator** | [`../../generator/generate_answer_quality_corpus.py`](../../generator/generate_answer_quality_corpus.py) (Epic #3888, Issue #3890) |
| **Dokumente** | 13 Dokumente `aq-NN_*`: 8 Markdown, 1 PDF, 1 DOCX, 3 XLSX |
| **Fälle** | [`../../golden/answer-quality.json`](../../golden/answer-quality.json), 31 Fälle in drei Klassen und 3 Tabellen-Prüfungen |

## Zweck

Die Domäne misst, ob eine **vollständig beantwortbare** Frage eine brauchbare Antwort bekommt. Sie
misst nicht, ob die Suche das richtige Dokument weit oben findet (das tun die Retrieval-Domänen),
sondern drei Fehlerbilder der Antwort und, davor, ob das Material dafür im Prompt-Kontext ankommt:

| Klasse (`class`) | Fälle | Fehlerbild | Prüfung der Antwort |
|---|---|---|---|
| `false_negative` | 10 | falsche Fehlanzeige: Die Antwort steht im Suchbereich, das Modell sagt „nicht in den Quellen“ | Fehlanzeige-Muster oder fehlender Wert |
| `needless_caveat` | 10 | unnötiger Prämissen- oder Lückenhinweis bei vollständig beantwortbarer Frage | Hinweis-Muster, Fehlanzeige-Muster oder fehlender Wert |
| `wrong_reference` | 11 | falsche Bezugsgröße: die richtige Zahl, aber auf die falsche Einheit, Zeile, Spalte oder den falschen Zeitraum bezogen | verbotenes Muster (falsche Bezugsgröße) oder fehlender Wert samt Einheit |

Jede Antwort-Prüfung ist ein Muster, nie ein Modellurteil. Die Muster sind eine Stichprobe dessen,
wie deutsche Antworten Fehlanzeige und Hinweis formulieren; ein Fall in einer unbekannten
Formulierung zählt als sauber, die Quoten sind Untergrenzen.

## Aufbau

Die fiktive **Gemeinde Birkenstein** (getrennt von den Orten der anderen Korpora). Kennungen,
Formen und Längen sind gezielt so gewählt, dass sie die Fehlerbilder auslösen können:

- **Kennungen in Varianten:** `AB-08` in der Dienstanweisung (Fragen: `AB08`, `AB-08`, `AB 08`,
  `ab-08`), `FB12` im Formularverzeichnis (Fragen: `FB-12`, `FB12`, `FB 12`, `fb12`), `ZV-117` in
  der Zuständigkeitstabelle (Fragen: `ZV117`, `ZV-117`, `ZV 117`, `zv-117`). Die Varianten stehen
  als `question_variants` am Fall.
- **Lange Dokumente mit spätem Treffer:** `aq-01` (Marktsatzung, rund 15 000 Zeichen, die Gebühren
  stehen in § 18) und `aq-08` (Geschäftsordnung, rund 21 000 Zeichen, die Niederschrift in § 27).
- **Tabellen:** `aq-11` mit Titelzeile und Leerzeile über der Kopfzeile und 63 Datenzeilen (mehr als eine Zeilengruppe, die gefragten Zeilen stehen am Ende), `aq-12` als kleine Matrix,
  deren Antwort Zeile und Spalte braucht (Raum mal Nutzergruppe, dazu eine Kaution je Buchung statt
  je Stunde), `aq-13` mit 130 Zeilen, damit spätere Chunks den Kopf wiederholen müssen. `aq-10`
  (DOCX) trägt eine Tabelle und Fließtext, `aq-09` ist ein PDF.
- **Bezugsgrößen:** Jede Zahl trägt ihre Einheit im Text (je laufendem Meter und Markttag, je Essen,
  je angefangener Woche, je Jahr der Verlängerung, 50 Prozent des Entgelts statt der Kaution). Die
  Nachbarzahlen im selben Dokument sind der Verwechslungspartner.

Jedes Dokument trägt nur erfundene Beträge und Fristen. Der Generator schreibt PDF, DOCX und XLSX
selbst, mit festen ZIP-Zeitstempeln: Zwei Läufe erzeugen byte-identische Dateien.

## Fallschema

| Feld | Bedeutung |
|---|---|
| `id`, `class`, `question`, `documents` | Kennung (`aq-fn-`, `aq-nc-`, `aq-wr-`), Klasse, Frage, erwartetes Dokument |
| `question_variants` | weitere Schreibweisen der Frage; jede muss das Dokument in den Kontext bringen |
| `evidence` | Texte, die **alle in einem Abschnitt** des Kontexts stehen müssen: Wert samt Einheit; bei einer Tabelle die gerenderte Zeile (`Saal \| 25 \| 85 \| 60 \| 200`) samt den Zellen des Kopfs, die ihre Spalte benennen |
| `answer_must_match` | Muster, die alle auf die Antwort passen müssen (Wert, Einheit) |
| `answer_must_not_match` | Muster einer falschen Bezugsgröße; keines darf passen. Sie binden die Zahl an ihren Bezug (`Auswärtige\D{0,40}\b85\b`: keine weitere Zahl dazwischen), damit erläuternde Nachbarzahlen in einer richtigen Antwort nicht zählen |
| `canonical_answer`, `wrong_answer` | eine ausführliche richtige Antwort mit Nachbarzahlen, die alle Prüfungen besteht, und (nur `wrong_reference`) eine Antwort mit der falschen Bezugsgröße, auf die ein verbotenes Muster passen muss; `AnswerQualityDatasetTest` prüft beides |
| `tags` | `long_document`, `identifier_variants`, `table_title_row`, `table_row_column`, `table_late_row`, `pdf`, `docx_table` |
| `expected_state` | der zuletzt akzeptierte Zustand je Ebene, mit Datum und Grund |

`table_checks` prüfen je Tabelle, dass der Chunk der letzten Datenzeile die Kopfzellen trägt.

Die Antwort wird vor der Prüfung normalisiert (`AnswerQualityAnswers#clean`): Zitiermarken entfernt,
Tausendertrenner und `,00` entfernt (`1.000,00 €` liest als `1000 Euro`), `€`/`EUR` als `Euro`, `%` als
`Prozent`, Mitternacht als `24 Uhr`. Fehlanzeige-Muster zählen nur dort allein, wo sie die Quellen
nennen (`in den Quellen`) oder die Antwort verweigern; allgemeine Wendungen („nicht enthalten“) zählen
nur in einer Antwort ohne den erwarteten Wert.

## Ebenen und Zustände

| Ebene | Prüft | Wo | Docker |
|---|---|---|---|
| `format` | Schneiden die produktiven Formate die Datei so, dass ein Chunk Wert und Bezug trägt und der Tabellenkopf stimmt? | `AnswerQualityDatasetTest` (Teil von `evalUnitTest`, also von `check`) | nein |
| `context` | Kommt das Dokument über Einbettung, Suche, Fusion und Nachbar-Erweiterung im Prompt-Kontext an, auch für jede Kennungs-Schreibweise, und trägt ein Abschnitt allen Beleg? | `AnswerQualityEvaluationHarnessTest` (`./gradlew evaluateAnswerQuality`) | ja, oder Host-Ollama |
| Antwort | Antwortet ein echtes Modell ohne Fehlanzeige und Hinweis, mit der richtigen Bezugsgröße? | derselbe Lauf, ohne `-Dopaa.eval.answerQuality.skipAnswers=true` | ja, plus Modell-Endpunkt |

Der Zustand je Fall und Ebene ist `solved`, `known_gap` oder, solange die Ebene nie gemessen wurde,
`unmeasured`. Die Ebene `format` lässt `AnswerQualityDatasetTest` in **beide Richtungen** scheitern,
wenn der gemessene Zustand vom deklarierten abweicht: Ein Paket, das eine Lücke schließt, stellt
den Fall dort von `known_gap` auf `solved` um (im Generator, `FORMAT_GAPS`), und das Gegenteil ist
eine Regression. Die Ebene `context` und die Antworten führen nur Berichte; eine Abweichung steht
im Bericht, bricht aber den Lauf nicht ab.

### Ausgangswerte (2026-11-27, Stand vor den Paketen 4, 5 und 7 des Epics)

Ebene `format`: 28 von 31 Fällen `solved`, 3 `known_gap` (`aq-fn-07`, `aq-wr-06`, `aq-wr-07`; je Klasse
`false_negative` 9 von 10, `needless_caveat` 10 von 10, `wrong_reference` 9 von 11 gelöst). Ursache: Das
Tabellenformat liest die erste nichtleere Zeile als Kopf, in `aq-11` die Titelzeile. Der erste Chunk
trägt die echten Spaltenköpfe daher als Datenzeile, ab dem zweiten (Zeilengruppen zu 50 Zeilen) fehlen
sie ganz; die gefragten Zeilen stehen im zweiten Chunk, mit der Titelzeile als einzigem „Kopf“. Die
lange Tabelle `aq-13` wiederholt ihren Kopf in allen drei Chunks, die kleine Matrix `aq-12` und die
DOCX-Tabelle bleiben in einem Chunk.

Ebene `context` und Antworten: in der Umgebung, in der die Domäne entstand, gab es weder Docker noch
einen Modellschlüssel; alle Fälle tragen deshalb `unmeasured`. Der Erstlauf auf einer Maschine mit
Docker trägt den Ist-Zustand ein (`./gradlew evaluateAnswerQuality -Dopaa.eval.answerQuality.skipAnswers=true`
und Eintrag unter `expected_state.context`, danach `python eval/generator/generate_answer_quality_corpus.py`).

## Pflege

```bash
python eval/generator/generate_answer_quality_corpus.py
```

Der Generator schreibt Korpus, Manifest und Fälle und behält die gemessenen Zustände der Ebene
`context` aus der bestehenden Datei. Nach einer Korpusänderung ist die Ebene `context` neu zu
messen.
