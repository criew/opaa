# ADR-0026: Kontextpräfix-Abdruck je Dokument und modellgestützte Extraktion

## Status

Akzeptiert · zuletzt geändert 2026-09-17 · ergänzt ADR-0024, dessen Entscheidungen 1–8 unverändert gelten

## Kontext

Zwei Fähigkeiten des Epics #1065 deckt ADR-0024 nicht ab: Der **Kontextpräfix** (#1072) stellt
ausgewählte Metadatenwerte dem eingebetteten und volltextindizierten Text voran und macht jede
Schemaänderung an einem präfixwirksamen Feld zu einem Neu-Einbetten — welche Dokumente muss ein
Nachlauf anfassen, und wie wird „der angezeigte Preis ist der bezahlte Preis" eingelöst? Die
**modellgestützte Extraktion** (#1073) ist der erste Teil der Aufnahmestrecke, der Geld kostet,
ausfallen kann und Dokumentinhalte an ein Sprachmodell übergibt; die Spezifikation entscheidet die
Fachregeln, nicht die Betriebsmechanik. Beide hängen am selben Datenmodell und derselben
Nachlauf-Mechanik: Ein vom Modell vergebenes Schlagwort ändert den Kontextpräfix.

## Entscheidung

### 1. Die Auswahl des Präfix-Nachlaufs ist ein Abdruck je Dokument, keine Version je Bibliothek

`documents.context_prefix_stamp` trägt den Fingerabdruck des Präfix, mit dem die Chunks zuletzt
eingebettet wurden (`ChunkContextPrefix#stampOf` über Titel und präfixwirksame Werte). Eine
präfixwirksame Schemaänderung leert ihn bei genau den Dokumenten, deren Präfix sich ändert
(`ContextPrefixRerunService`). Eine Version je Bibliothek ließe die Vorschau „12 Dokumente" sagen
und den Lauf 10.000 neu einbetten; die Zahl der Vorschau ist die Menge des Laufs. Der Preis ist
ein Schreibvorgang je betroffenem Dokument beim Speichern der Schemaänderung.

### 2. Ein Gate für beide Schreibwege des Präfix

`ChunkContextPrefix#forChunk` ist die einzige Stelle, an der ein Präfix entsteht — Aufnahmeweg wie
Nachlauf; zwei Formeln drifteten erst an schwankenden Suchergebnissen auffällig auseinander. Die
Aufnahmeentscheidung `documents.context_prefix_eligible` wird vom Nachlauf respektiert, nicht neu
geraten. Der Präfix ist Teil der indizierten Darstellung, nie des gespeicherten Chunk-Textes: Der
Auszug im Beleg bleibt der Originalwortlaut.

### 3. Folgekosten sind eine Auswahl, keine Schätzformel

`LibraryMetadataFieldService#changeImpact` beantwortet eine geplante Änderung mit betroffenen
Dokumenten, Chunks, Einbettungsaufrufen und erwarteter Laufzeit. Die Laufzeit stammt aus der
gemessenen mittleren Dauer je Chunk (`EmbeddingRateEstimator`); gemessen wird nur, was allein lief.
Solange zu wenige Aufrufe gemessen sind, gilt der konfigurierte Schätzwert, und die Antwort sagt,
welcher der beiden zählt.

### 4. Die modellgestützte Extraktion ist voreingestellt aus, je Bibliothek, mit zwei getrennten Marken

`knowledge_libraries.model_extraction_enabled` und `keywords_enabled` stehen ab Werk auf `false`,
auch für jede ältere Bibliothek. Die Abtragsmarken des Bestandslaufs sind zwei
(`documents.model_extraction_version`, `keyword_extraction_version`), damit eine Bibliothek, die
zuerst nur mit Schlagworten lief, ihren Altbestand erreicht, wenn die Modell-Extraktion später
dazukommt. Der Modellschritt trägt eine eigene Versionsnummer (`ModelMetadataExtractor.EXTRACTION_VERSION`)
getrennt von `CoreMetadataExtractor`: Eine korrigierte Regel in Schritt 1 darf nicht jedes Dokument
jeder eingeschalteten Bibliothek zu einem bezahlten Modellaufruf machen.

### 5. Die Konfidenzschwelle ist vorab festgelegt; Senken verlangt eine neue Messung, Anheben nicht

`ModelMetadataExtractor.CONFIDENCE_THRESHOLD` ist 0,80 für alle modellbefüllten Felder,
freigegeben nach der Regel von [ADR-0012](0012-messvertrag-retrieval-harness.md). Ein Wert außerhalb
der angebotenen Werteliste wird unabhängig von der Konfidenz verworfen — die serverseitige Prüfung
gegen die Liste ist die einzige bindende Schranke gegen ein präpariertes Dokument; die Kennzeichnung
des Textes als Inhalt statt als Anweisung ist eine Minderung, keine Zusicherung. Der Schaden ist
asymmetrisch (ein halluzinierter Wert ist schlimmer als keiner); jede Änderung bleibt ein Commit
mit Datum und gemessener Verteilung.

### 6. Ein Modellaufruf hat einen eigenen Pool, eine Ablehnungsregel und ein Client-Zeitlimit

Der Aufruf läuft auf `modelExtractionTaskExecutor` (beschränkt, Warteschlange 0, `AbortPolicy`),
nicht auf dem gemeinsamen `ForkJoinPool`. Ist der Pool voll, unterbleibt der Aufruf — eigener
Zähler, kein Inline-Lauf —, und das Dokument bekommt keine Abtragsmarke, bleibt also in der Auswahl
des Bestandslaufs; eine Zeitüberschreitung dagegen wurde bezahlt und gilt als erledigt. Ein
überschrittener Aufruf wird aufgegeben, nicht abgebrochen; beendet wird er vom Anfrage-Zeitlimit
des Clients (`ActiveChatModelResolver#resolveChatClient(Duration)`).

### 7. Freie Schlagworte leben in einer eigenen Tabelle

`document_keywords` statt einer weiteren Zeile in `document_metadata_values`: Jede Zeile dort ist
ein typisiertes Feld, das ein Filter benennen darf — genau das darf ein Schlagwort nie werden.
Schlagworte erreichen Einbettung und Volltextindex ausschließlich als ein Segment des
Kontextpräfix und tragen keinen Chunk-Schlüssel.

## Konsequenzen

- **Einfacher:** Der Nachlauf benutzt dieselbe Chargen-Schleife wie Bestandslauf und
  Pipeline-Reindex; eine manuelle Korrektur eines präfixwirksamen Wertes stellt genau ein Dokument
  in denselben Lauf.
- **Schwieriger:** Jede Änderung an der Präfixbildung selbst ändert den Abdruck für den ganzen
  Bestand und beauftragt damit einen vollständigen Nachlauf, der auszuweisen ist.
- **Restrisiko:** Die modellgestützte Extraktion ist gebaut, aber **nicht abgenommen**: Die
  Handstichprobe (#1359) wies überwiegend falsche Werte oberhalb der Schwelle aus, weil das
  ausgelieferte Vokabular für den häufigsten Verwaltungsbestand keinen passenden Wert hatte.

## Verworfene Alternativen

- `context_prefix_version` je Bibliothek — Vorschau und Lauf fielen auseinander.
- Schlagworte als Zeile in `document_metadata_values` — würden filterbar.
- Modellaufrufe auf dem `ForkJoinPool` — das Zeitlimit liefe an nie gestarteten Aufrufen ab.
- Volle Warteschlange inline ausführen — das Zeitlimit griffe gerade dann nicht.

## Offen

- Nachkalibrierung der modellgestützten Extraktion (#1359, geparkt): Schwelle 0,90, erweitertes
  Vokabular, Negativbeispiele im Prompt, erneute Handstichprobe vor einer Abnahme.
