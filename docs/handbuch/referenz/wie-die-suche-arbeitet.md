# Wie die Suche arbeitet

Eine Frage geht nicht einfach „an das Sprachmodell“. opaa sucht zuerst im Index, und nur die
gefundenen Textstücke (Chunks) gehen mit der Frage an das Chat-Modell. Das Modell formuliert, die
Suche entscheidet: **Was nicht gefunden wurde, kann in keiner Antwort stehen.**

```mermaid
flowchart LR
    F[Frage] --> S[Suchbereich<br/>und Filter]
    S --> T[Teilfragen]
    T --> V[Vektorsuche]
    T --> L[Volltextsuche]
    V --> Z[Zusammenführen<br/>und Ordnen]
    L --> Z
    Z --> A[Antwort]
    A --> B[Belege<br/>prüfen]
```

## Die Stufen

| Stufe | Was passiert |
|---|---|
| Suchbereich | die Bibliotheken des Space, die die Person lesen darf. Die Rechte stecken in der Datenbankabfrage selbst; eine fremde Bibliothek kommt nie vor, auch nicht für die Systemverwaltung |
| Filter | gesetzte [Metadaten](metadaten.md)-Filter schränken beide Suchwege ein; Dokumente ohne Wert bleiben drin |
| Teilfragen | das Chat-Modell macht aus der Frage eigenständige Suchanfragen: zwei Themen werden zwei Anfragen, „Und bei Bedürftigkeit?“ wird zur vollständigen Frage |
| Vektorsuche | findet Chunks mit ähnlicher **Bedeutung** |
| Volltextsuche | findet die **Wörter**, mit deutscher Wortstammbildung; Paragrafen und Aktenzeichen exakt, sodass „§ 34“ nicht „§ 35“ trifft |
| Fusion | führt beide Listen nach Rang zusammen; wer in beiden steht, gewinnt |
| Reranking | ein eigenes Modell liest Frage und Chunk zusammen und ordnet neu. Ab Werk aus, weil es einen eigenen Endpunkt braucht |
| Relevanzschnitt | schneidet schwache Treffer ab, wenn eingeschaltet |
| Vervollständigung | holt zu einem schon gefundenen Dokument weitere Chunks nach. Ab Werk aus; den Zusammenhang liefern die Nachbar-Chunks |

Vor der Antwort bekommt jeder Treffer seine **Nachbar-Chunks** dazu, damit eine Tabelle oder
Aufzählung an einer Chunk-Grenze nicht abreißt.

## Die Antwort

Das Chat-Modell bekommt die Chunks, die **Anweisung des Space**, die Gesprächsnotiz und den Verlauf.
Es soll nur wiedergeben, was die Dokumente tragen, Fehlendes als „in den vorliegenden Dokumenten
nicht enthalten“ benennen und jede Quelle mit einer Zitiermarke belegen. Der Text der Dokumente
steht als Material, nicht als Anweisung im Prompt; ganz verhindern lässt sich eine untergeschobene
Anweisung nicht, das hängt am Modell.

**Fehlanzeigen prüft opaa nach.** Sagt die Antwort, etwas stehe nicht in den Dokumenten, etwa „die
Gebühr der Tarifstelle AB-08 ist in den Dokumenten nicht enthalten“, sucht opaa die Kennungen und
Schlüsselbegriffe der Frage noch einmal im selben Suchbereich, mit denselben Rechten und Filtern.
Findet es Textstellen, die diese Begriffe enthalten und die die Antwort noch nicht kannte, antwortet
das Modell genau einmal neu mit ihnen; die neue Antwort ersetzt die erste, auch während sie im Chat
entsteht. Ohne solche Stellen, bei einem Fehler der Nachprüfung oder einer leeren neuen Antwort
bleibt die erste. Erkannt wird die Fehlanzeige an festen deutschen und englischen Formulierungen,
die sich auf die Dokumente beziehen, ohne weiteren Modellaufruf. Nachgeprüft wird nur, wenn sie die
Antwort trägt: Die Antwort hat keinen bestätigten Beleg, oder die Fehlanzeige steht vor oder in dem
ersten belegten Satz. Ein Vorbehalt nach einer belegten Antwort, etwa „Weitere Details sind in den
Quellen nicht genannt“, bleibt, wie er ist. Im Agentenlauf bekommt das Modell die Stellen als
Nachfrage im laufenden Lauf, und die Nachsuche steht als Schritt im Lauf.
Abschalten lässt sich das mit `OPAA_QUERY_ABSENCE_RECHECK_ENABLED`.

Je Frage ruft opaa nach außen höchstens: Chat-Modell für Teilfragen und Antwort, Embedding-Modell
je Teilfrage, Rerank-Modell, falls an; nach einer Fehlanzeige einmal mehr Embedding- und
Rerank-Modell für die Nachprüfung und, wenn sie neue Textstellen findet, das Chat-Modell für die
neue Antwort. Titel und Gesprächsnotiz eines Chats kosten je einen
weiteren Chat-Aufruf, ohne die Antwort zu verzögern.

## Belege prüfen

Den Zitiermarken glaubt opaa nicht, es prüft sie ohne weiteren Modellaufruf:

1. **Herkunft:** Zeigt die Marke auf einen Chunk, den die Antwort wirklich gelesen hat?
2. **Fakt:** Steht ein Betrag, Datum oder Paragraf im Satz vor der Marke so auch im Dokument? Nur
   ein abweichender Wert derselben Art stuft herunter.

Was durchfällt, bleibt im Text und heißt an der Fundstelle **„Beleg nicht bestätigt“**. Daraus
folgt der Belegstand der Antwort: **Belegt**, **Teilweise belegt** oder **Nicht belegt**. Er sagt,
worauf sich die Antwort stützt, nicht, ob sie stimmt. Wie man das im Chat liest:
[Belege prüfen](../benutzer/belege-pruefen.md).

## Wenn etwas nicht gefunden wird

Fast jede schlechte Antwort ist zuerst ein Suchproblem. Die Diagnose unter
[Suche und Indexierung überwachen](../administration/suche-und-indexierung.md) zeigt Stufe für Stufe, wo
ein Dokument verloren geht.

| Befund | Übliche Ursache |
|---|---|
| nie gefunden | nicht im Suchbereich, nicht indiziert, Scan ohne Text, Begriff steht so nicht im Dokument |
| gefunden, aber hinten | nur ein Suchweg hat es gefunden; präziser fragen, Reranking einschalten |
| „Beleg nicht bestätigt“ | Modell hat eine Marke erfunden oder einen Wert anders wiedergegeben |
| Reranking „nicht verfügbar“ | Endpunkt nicht erreichbar; die Suche lief ohne weiter |
| viele „ohne Angabe“ unter Filter | Feld im Bestand schwach gefüllt; Metadaten nachpflegen |
| Antwort auf „Danke“ mit Quellen | gewollt: auch eine Bemerkung wird gesucht |

Grenzwerte und Schalter der Suche (`OPAA_SEARCH_*`, `OPAA_QUERY_*`):
[Konfiguration](konfiguration.md#grenzwerte-im-überblick).
