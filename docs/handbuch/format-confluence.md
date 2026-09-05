# Format: Confluence-Seite

> **Entwurf.** Pipeline `confluence`, Version 1. Der gemeinsame Rahmen aller Format-Pipelines
> steht im Kapitel [Indexierung](indexierung.md), Abschnitt 5; wie die Seiten in die Bibliothek
> gelangen, im Kapitel [Confluence](konnektor-confluence.md).

## 1. Wann sie läuft

Diese Pipeline beansprucht kein Dateiformat. Sie bekommt den Inhalt einer Confluence-Seite, wie ihn
der Konnektor von der Instanz erhält: XHTML im Storage-Format mit den Makro-Elementen `ac:*` und
`ri:*`, für Cloud und Data Center identisch. Der Konnektor ruft sie direkt auf; eine
Formaterkennung findet nicht statt, weil es keine Datei gibt.

Anhänge einer Seite laufen **nicht** über diese Pipeline, sondern wie jede andere Datei nach
Inhalt zu ihrer Format-Pipeline (ein PDF-Anhang zur PDF-Pipeline, ein HTML-Anhang zur
HTML-Pipeline).

## 2. Was gelesen wird

Der Seitenkörper wird mit einem XML-Parser gelesen, damit die Makro-Elemente erhalten bleiben. Die
Kernfrage ist nicht Boilerplate wie bei HTML, sondern **welcher Makro-Inhalt Seiteninhalt ist** und
welcher erst beim Anzeigen aus anderen Quellen zusammengesetzt wird. Die Trennlinie ist, wo der
Inhalt lebt:

| Makro-Klasse | Beispiele | Was davon Text wird |
|---|---|---|
| Statischer Inhalt mit Rich-Text-Körper | `info`, `note`, `warning`, `tip`, `panel`, `expand`, `details` (Seiteneigenschaften), `excerpt`, `section`, `column`, jedes unbekannte Makro | Titel-Parameter als eigene Zeile, Körper als Seitentext; alle anderen Parameter entfallen |
| Wörtlicher Text | `code`, `noformat` | Klartextkörper mit Zeilenumbrüchen, Sprachangabe als vorangestellte Zeile |
| Parameter ist der Inhalt | `status`, `lozenge` | Titel-Parameter als Inline-Text |
| Zur Laufzeit erzeugt | `toc`, `children`, `pagetree`, `recently-updated`, `contentbylabel`, `livesearch`, `create-from-template`, `attachments`, `gallery`, `tasks-report`, `page-properties-report`, `chart`, `calendar`, `roadmap`, `profile`, `userlister`, `contributors`, `widget`, `iframe`, `html`, `rss`, `jira`, `jiraissues`, `sql`, `include`, `excerpt-include`, `multiexcerpt-include` und weitere Berichte und Einbettungen | nichts; Navigationshilfe oder Kopie von Daten, die in einem anderen System liegen |

Ein unbekanntes Makro gilt als statisch: Ein Rich-Text-Körper ist vom Autor geschriebener Text,
alles andere daran nicht. Die Anhangsliste einer Seite entfällt als Makro, weil die Anhänge selbst
als Dokumente indiziert werden.

Was nie Text wird: Bilder, Emoticons, Platzhalter, Ressourcenverweise (`ri:*`), Link-Ziele.
Der **Anzeigetext eines Links** bleibt. Ein `time`-Element wird durch sein Datum ersetzt. Elemente
des neuen Cloud-Editors werden über ihren Inhalt gelesen; die wiederholte Altkopie (`ac:adf-fallback`)
bleibt unsichtbar, damit nichts doppelt erscheint.

## 3. Struktur und Chunks

```mermaid
flowchart TB
    S[Seitenkörper] --> H[Überschriften h1–h3<br/>schneiden Chunks]
    S --> T[Tabellen: eine Zeile je<br/>Tabellenzeile, Zellen mit „ | “]
    S --> L[Listen: eine Zeile je Eintrag<br/>mit Marker •, ◦, ▪ oder „2.1.“]
    S --> M[Makros nach Regelwerk]
    H --> C[Chunks mit Überschriftenpfad<br/>als erster Zeile und als Ortsangabe]
    T --> C
    L --> C
    M --> C
```

- **Überschriften** h1 bis h3 öffnen einen neuen Chunk, h4 bis h6 falten in den Text, wie bei
  HTML und Markdown. Der Überschriftenpfad steht als erste Zeile im Chunk und als Ortsangabe
  („Abschn. Fristen › Verlängerung"). Ein Makro in einer Überschrift trägt nur seinen sichtbaren
  Text zum Pfad bei.
- **Tabellen** werden eine Zeile je Tabellenzeile, Zellen mit „ | " getrennt. Eine Tabelle in einer
  Zelle wird in diese Zelle geglättet. Listen und Makros in einer Zelle werden zu einer Zeile.
- **Listen** werden eine Zeile je Eintrag; die Verschachtelung trägt der Marker (•, ◦, ▪ nach
  Tiefe, bei nummerierten Listen „2.1."), weil eine Einrückung den Zuschnitt nicht überlebt.
- **Aufgabenlisten** behalten ihren Zustand (`[x]` / `[ ]`).
- **Code** und `noformat` behalten ihre Zeilenumbrüche.
- Leerzeilen des Editors (`<p>&nbsp;</p>`) werden entfernt.

Die Chunk-Größen sind fest: 4.000 Zeichen weich je Abschnitt, 20.000 Zeichen hart, dieselben
Werte wie bei den anderen überschriftengetriebenen Pipelines.

## 4. Metadaten am Chunk

| Feld | Inhalt |
|---|---|
| `location` | Überschriftenpfad innerhalb der Seite |
| `source_container_key` | Space-Schlüssel der Seite |
| `source_hierarchy_path` | Gliederungspfad der Seite: die Titel der übergeordneten Seiten, von der Wurzel her mit „ / " verbunden |
| `file_name` | Seitentitel |

Space und Gliederungspfad stehen nicht im Seitenkörper; der Konnektor kennt sie und schreibt sie an
das Dokument und an jeden Chunk. Der **Kontexttitel** für das Embedding ist der Ort der Seite im
Space, Gliederungspfad plus Titel („Handbuch / Kapitel 1 / Abschnitt 1.1"), nicht nur der Titel.

**Dokumenteigenschaften** für das [Metadatenschema](metadaten.md): der Seitentitel als Titel. Ein
Seitentitel ist kein Dateiname und liefert weder Dokumentart noch Datum/Stand; die Versionsnummer
ist kein Datum. Beide Felder bleiben für Confluence-Seiten leer, solange sie nicht von Hand
gesetzt werden.

## 5. Fehler

| Befund | Ergebnis |
|---|---|
| Seitenkörper leer | Seite bekommt keine Dokumentzeile, Protokolleintrag „Kein Inhalt extrahierbar"; ihre Anhänge werden trotzdem indiziert |
| Körper vorhanden, aber nach dem Regelwerk kein Text (nur dynamische Makros, nur Bilder) | abgewiesen, „kein extrahierbarer Text" |
| Seite mit gleichem Inhalt unter neuer Version (Titeländerung, Umzug) | übersprungen; Titel, Gliederungspfad und Versionsmarke werden aktualisiert |

## 6. Nicht verarbeitet

- Das ADF-Format der Cloud (`atlas_doc_format`); das Storage-Format ist das gemeinsame
  Zwischenformat beider Editionen
- Seiteneigenschaften (`details`-Makro) als Dokumenteigenschaften für das Metadatenschema; sie
  werden nur als Text indiziert
- Inhalte, die Makros aus anderen Seiten oder Systemen einbinden (`include`, `excerpt-include`,
  Jira); wer sie braucht, nimmt die Quellseite in die Auswahl auf
- Kommentare, Versionen außer der aktuellen, Bildinhalte
