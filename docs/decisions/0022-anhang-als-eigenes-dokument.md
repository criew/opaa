# ADR-0022: Ein Anhang ist ein eigenes Dokument

## Status

Akzeptiert · zuletzt geändert 2026-09-04 · ergänzt durch ADR-0023 (Entscheidung 4)

## Kontext

Für dieselbe fachliche Sache gab es zwei Muster. RSS lud jeden auf der Detailseite gefundenen Anhang
einzeln und machte ihn zu einer eigenen `Document`-Zeile mit eigener Prüfsumme, eigener Quote und der
Pipeline-Kennung seines Formats; die Klasse war aber an den RSS-Lauf gebunden. Mail routete einen
Anhang zwar durch die Format-Pipeline, hängte die Chunks aber an die flache Chunk-Liste der Mail, und
jeder Chunk trug die Kennung der Mail-Pipeline, auch der eines PDF-Anhangs; ein Nachzug nach
Pipeline-Version erreichte Mail-Anhänge deshalb nicht. Mit Confluence stand ein dritter Anhangsweg
bevor. Der Maintainer hat entschieden: Ein Anhang, an einer E-Mail wie an einer Confluence-Seite, ist
eine eigene `Document`-Zeile, nie ein verschachtelter Chunk seines Elterndokuments. Dieser ADR denkt
das zu Ende: Identität, Löschsemantik, Elternbeziehung, Sichtbarkeit, Quote, Änderungserkennung,
Paketschnitt, Bestandsmigration. Anschlusspunkt ist ADR-0017 Entscheidung 5: Weil RSS nie durch
Abwesenheit löscht, hatte bis dahin kein Anhangsweg beantworten müssen, wie ein Anhang nicht als
verschwunden gilt; ADR-0018 bleibt unberührt, ein Anhang liegt in der Bibliothek seines Elternteils.

## Entscheidung

### 1. Grundsatz

Jeder Anhang (E-Mail, Confluence-Seite, RSS-Detailseite) wird über denselben Anhangsweg zu einer
eigenen `Document`-Zeile in derselben Bibliothek wie sein Elterndokument: eigene Prüfsumme, eigener
Lebenszyklus, eigene `pipeline_id`/`pipeline_version` durch die für sein Format zuständige Pipeline.

### 2. Identität: der `file_path` schließt den Elternpfad ein

`(library_id, file_path)` bleibt eindeutig. Bei RSS und Confluence ist der `file_path` die stabile
Anhangs-URL oder -Kennung der Quelle. Ein Mail-Anhang hat keine URL; sein `file_path` ist
`<Elternpfad>/<Index>/<Name>` (`AttachmentFilePath`), rekursiv für Mail in Mail. Kein echter
Dateipfad kann diese Form haben, weil der Elternpfad eine Datei benennt. Daraus folgen
Eindeutigkeit und Stabilität über Läufe, solange die Mail-Datei nicht verschoben wird. Der Index ist
die Position in der Liste, die der ungefilterte Lauf meldet; eine Anlage, die dieser Lauf nicht
meldet, verbraucht keine Position.

### 3. Löschsemantik: Anhangspfade zählen zum Lauf

Der Anhangsweg meldet jeden Anhangspfad, den er in einem Lauf antrifft (angelegt oder unverändert
bestätigt), an den Lauf zurück (`IndexingRun`, `ReconcilingAttachmentAccess`), der ihn mit den
Elternpfaden in die Menge des aktuellen Bestands aufnimmt; `StaleDocumentCleanupService` behandelt
Eltern- und Anhangszeile gleich. Verschwindet das Elterndokument, fallen seine Anhänge im selben
Abgleich weg; verliert es einen Anhang, fällt nur dieser weg. Wird ein Elterndokument als unverändert
übersprungen, werden seine Anhänge nicht neu entdeckt; ihre Pfade werden dann aus der Datenbank über
die Elternbeziehung nachgetragen, sonst räumte der Abgleich sie fälschlich ab. Jeder andere Pfad, der
ein Elterndokument entfernt oder unter seiner ID ersetzt (heute `PipelineReindexService` und die
Einzellöschung in `LibraryDocumentService`, künftig jeder weitere), entscheidet ausdrücklich, was
mit den Anhangszeilen geschieht (mitlöschen, lassen, bei Ersetzung auf Fortbestand prüfen); einen
Automatismus dafür gibt es nicht.

### 4. Elternbeziehung: `documents.parent_document_id`

Ein Anhang jedes Quellentyps setzt `parent_document_id` (nullable, Fremdschlüssel auf `documents`
mit `organization_id`, ohne `ON DELETE CASCADE`) und daneben `sourceEntryUrl` auf den Elternpfad,
das Feld, das die Beleg-Anzeige liest. Ein Fremdschlüssel, weil „alle Anhänge von X“ abfragbar sein
muss (Entscheidung 3). Keine Kaskade, weil die Chunks im Vektorspeicher nur über Anwendungscode
erreichbar sind. Der Elternbezug steht am Anhangsdokument, nicht mehr als Textpräfix im Chunk.

### 5. Sichtbarkeit: gruppiert unter dem Elterndokument

Die Dokumentliste zeigt Anhänge unter ihrem Elterndokument, nicht als flache Zeilen; Paginierung,
Sortierung und Gesamtzahl bemessen sich an den Elterndokumenten, Anhänge erscheinen auf der Seite
ihres Elternteils (#1184). Grund: Eine Mail mit drei Anhängen wird vier Zeilen; ohne Gruppierung
wirkte das als unerklärte Vervielfachung.

### 6. Quote und Budgets

Die parse-seitige Härtung (`max-message-bytes`, `max-attachment-bytes`,
`max-attachments-per-message`) bleibt bei `MailProperties`. Was der Anhangsweg indiziert, begrenzen
`AttachmentProperties` (`opaa.indexing.attachments.*`: Verschachtelungstiefe für alle Konnektoren,
Vorgabe 5, dazu Anzahl und Größe als geteilte Vorgabe) und `AttachmentLimits` (Anzahl und Größe,
wo ein Konnektor eigene Werte hat). Das `fileSize` eines Mail-Elterndokuments zählt nur Kopfdaten und
Nachrichtentext; der Anhang zählt seine eigenen Bytes gegen die Speicherquote, sonst zählte er doppelt.

### 7. Änderungserkennung

Mit eigener Zeile hat ein Anhang eine eigene Prüfsumme und bei Confluence eine eigene Version. Eine
geänderte Seite mit unverändertem Anhang wird neu verarbeitet, der Anhang über seinen `file_path`
gefunden und unverändert übersprungen: kein erneuter Download, kein Parsen, kein Embedding.
Verschachtelte Chunks können das nicht, weil jede Änderung am Elterndokument alle Chunks neu aufbaut.

### 8. Ort des Anhangswegs

Der Vertrag liegt in `io.opaa.indexing.attachment` (`AttachmentSource`, `AttachmentAccess`,
`AttachmentLimits`, `AttachmentOutcome`, `AttachmentProfile`), der Weg selbst, `AttachmentIndexer`,
als eine geteilte Bean in `io.opaa.indexing.document`, weil jeder Anhang durch die eine
Aufnahmekette geht. Konnektoren, die herunterladen, liefern ihren Zugriffsweg; Mail liefert bereits
extrahierte Bytes ohne Download-Schritt. Ein Format gibt gefundene Anhänge als `DiscoveredAttachment`
in `DocumentFormatResult` zurück, getrennt von seinen eigenen Chunks; dieser zweite Rückgabekanal ist
Teil des Format-Vertrags. Das Paket kennt vom Rest nur das Protokoll des Laufs; `document` und alles
darüber nutzen es, nie umgekehrt.

### 9. Bestandsmigration über die Pipeline-Version

Es gibt kein Migrationsskript und keinen selbsttätigen Start. Die Versionsnummer des Mail-Formats
wurde erhöht; alte, noch verschachtelte Mail-Chunks sind über die Version am Chunk erkennbar und
werden von der Betreiberin über die vorhandene Pipeline-Nachzugsmechanik
(`docs/features/ingestion-pipelines.md`, Teil 4) selektiv neu erzeugt. Ein gemischter Bestand ist
zulässig; der von der Betreiberin gewählte Zeitpunkt ist mehr wert als erzwungene Einheitlichkeit.

### 10. Das Mail-Format verarbeitet Anhänge nicht mehr selbst

`MailDocumentFormat` extrahiert Anhänge weiterhin, gibt sie aber über Entscheidung 8 zurück, statt
sie rekursiv zu verarbeiten. Routing, Fehlerbehandlung je Anhang („ein defekter Anhang kostet nur ihn
selbst“) und Formatzulassung liegen im gemeinsamen Weg. Dass Anhang-Chunks die Kennung der
Mail-Pipeline trugen, löst sich damit strukturell auf.

## Konsequenzen

**Einfacher:** Confluence und jeder weitere Konnektor bauen gegen einen Anhangsweg; die Beleg-Anzeige
ist für jeden Anhang einheitlich (eigenes Dokument, eigener Deep Link); Änderungserkennung ist
granular. Das Original eines Anhangs bleibt öffenbar, ohne seine Bytes zu speichern: Der
Content-Endpunkt extrahiert es beim Zugriff aus dem Elternoriginal nach, nur die angeforderte Anlage
(#1239, #1243).

**Schwieriger:** Ein Mail-Bestand zeigt nach dem Nachzug deutlich mehr Dokumentzeilen. `fileSize`
einer Mail hat eine neue Bedeutung. Jeder Lesezugriff auf ein Anhangsoriginal parst das
Elterndokument erneut und bei Konnektorbeständen ruft es das Original vollständig ab; die
gleichzeitig laufenden Nachextraktionen deckelt `AttachmentExtractionLimiter`
(`opaa.documents.attachment-extraction.max-concurrent`, Vorgabe 4; wer innerhalb von
`acquire-timeout` nicht an die Reihe kommt, erhält 429), Abrufe desselben Elterndokuments laufen
nacheinander. Der Deckel begrenzt nicht die Lebensdauer der geschriebenen Datei und nicht die Zahl
offener Antworten; das tut das Rate-Limit des Endpunkts.

## Verworfene Alternativen

- Verschachtelte Chunks mit korrigierter Pipeline-Kennung — behebt nur das Symptom, lässt Identität,
  Quote und Änderungserkennung dokumentweit und hält zwei Anhangsmodelle.
- `ON DELETE CASCADE` auf `parent_document_id` — hinterließe verwaiste Chunks.
- Bytes-Cache für wiederholte Anhangsabrufe — zweiter Speicherort mit doppelter Quotenzählung
  (Begründung in `docs/features/ingestion-pipelines.md`).

## Offen

Nichts. Die Form der Mail-Kopfdaten als Struktur-Metadaten ist nicht Gegenstand dieses ADR.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-09-02 | #1163 | Erstfassung samt fünf am selben Tag entschiedenen Punkten | 1–10 |
| 2026-09-03 | #1166, #1201 | Versionsnummer des Mail-Formats zweimal verschoben | 9 |
| 2026-09-04 | #1239, #1243 | Nachextraktion beim Öffnen, selektiv und gedeckelt | Konsequenzen |
