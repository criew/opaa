# ADR-0020: Ordner in Bibliotheken als Navigation, keine Rechtegrenze

## Status

Akzeptiert

Die Entscheidung wurde am 23.08.2026 vom Maintainer getroffen, festgehalten in Epic #520.

## Kontext

Epic #520 sieht Ordner für Wissensbibliotheken vor: In einer `UPLOAD`-Bibliothek soll sich der Bestand
wie in einer Dateiablage strukturieren lassen — Ordner anlegen (auch leer), umbenennen, löschen, Dateien
hineinladen. Für `FILESYSTEM`-Bibliotheken soll die tatsächliche Verzeichnisstruktur der Quelle als
read-only Ordner sichtbar werden, statt gleichnamige Dateien aus verschiedenen Unterordnern
ununterscheidbar in einer flachen Liste zu zeigen.

Bevor das gebaut wird, sind zwei Fragen zu klären, die sonst in der Umsetzung unbemerkt falsch entschieden
würden:

1. **Ist ein „Ordner in einer Bibliothek" derselbe Begriff wie der, den `docs/CONCEPTS.md` und
   `docs/VISION.md` bewusst ausschließen** — „die Wissensbibliothek ist … kein Ordner in einem Raum"? Wenn
   ja, widerspräche dieses Epic der bisherigen Konzeptentscheidung.
2. **Wie wird die Ordnerstruktur technisch abgebildet** — als eigene Entität mit Eltern-Kind-Beziehung,
   oder als abgeleiteter Pfad-Präfix am Dokumentnamen (etwa `Protokolle/2026/Januar.pdf`), wie es einige
   dateibasierte Systeme tun, um ohne zusätzliche Tabelle auszukommen?

## Entscheidung

### 1. Abgrenzung: „kein Ordner in einem Raum" betrifft Bibliothek ↔ Space, nicht die Struktur innerhalb einer Bibliothek

Die Aussage in `docs/CONCEPTS.md` („Die Wissensbibliothek ist ein KI-Asset und kein Ordner in einem
Raum") und in `docs/VISION.md` (Wissensbibliotheken sind „eigene Objekte mit eigenen Rechten … nicht bloß
Ordner in einem Arbeitsraum") bezieht sich auf das Verhältnis zwischen **Bibliothek und Space**: Die
Bibliothek trägt ihre Rechte selbst und lässt sich nicht durch einen Space-Wechsel umgehen — sie ist kein
Unterverzeichnis eines Space, dessen Rechte sie erbt.

Ordner **innerhalb** einer Bibliothek sind davon unberührt. Sie strukturieren den Bestand einer einzelnen
Bibliothek, deren Rechteanker unverändert die Bibliothek selbst bleibt (siehe Entscheidung 3). `docs/VISION.md`
nennt „Upload von Dateien und Ordnern" sogar ausdrücklich als Ziel für Meilenstein 1. Es gibt
also keinen Konzeptkonflikt — nur zwei verschiedene Bedeutungsebenen von „Ordner", die dieser ADR
auseinanderhält.

### 2. Echte Ordner-Entität statt virtueller Pfad-Präfixe

Eine neue Tabelle `library_folders` bildet die Struktur ab:

| Spalte | Bedeutung |
|---|---|
| `id` | Primärschlüssel |
| `library_id` | Bibliothek, zu der der Ordner gehört |
| `parent_folder_id` | übergeordneter Ordner; `NULL` bedeutet Wurzelebene der Bibliothek |
| `name` | Anzeigename des Ordners |

Ein Unique-Index auf `(library_id, parent_folder_id, name)` verhindert zwei gleichnamige Ordner auf
derselben Ebene. `documents` erhält ein nullables `folder_id`; `NULL` bedeutet, das Dokument liegt auf der
Wurzelebene der Bibliothek.

**Verworfen: virtuelle Pfad-Präfixe.** Statt einer eigenen Tabelle ließe sich ein Ordnerpfad auch als
String-Präfix am Dokumentnamen führen (`"Protokolle/2026/Januar.pdf"`), abgeleitet statt gespeichert. Das
scheitert an zwei Anforderungen, die dieses Epic ausdrücklich stellt:

- **Leere Ordner müssen existieren können.** Der typische Arbeitsablauf ist: erst die Struktur anlegen
  („Protokolle", „Rechtsquellen", „Archiv/2025"), dann füllen. Ein Pfad-Präfix existiert nur, solange
  mindestens ein Dokument ihn trägt — ein leerer Ordner wäre in diesem Modell unsichtbar, sobald das
  letzte Dokument daraus verschwindet, und könnte vorab gar nicht angelegt werden.
- **Umbenennen und Verschieben wären eine String-Kaskade statt eines Einzeiler-Updates.** Einen Ordner mit
  tausend Dokumenten umzubenennen hieße, den Präfix aller tausend Dokumentnamen zu ändern — mit allen
  Nebenwirkungen für Nebenläufigkeit, Historie und Fehlerfälle einer solchen Massenänderung. Mit einer
  eigenen Entität ist Umbenennen ein Update von `library_folders.name`, Verschieben ein Update von
  `parent_folder_id` — jeweils eine Zeile, unabhängig davon, wie viele Dokumente im Ordner liegen.

### 3. Grants bleiben ausschließlich auf Bibliotheksebene — Ordner sind keine Rechtegrenze

`asset_grants` referenziert weiterhin ausschließlich `library_id`. Es gibt keine ordnerspezifische
Berechtigung und keinen Plan, eine einzuführen. Wer eine Bibliothek lesen darf, sieht alle ihre Ordner und
deren Inhalt; wer sie bearbeiten darf, darf in jedem ihrer Ordner anlegen, umbenennen und löschen. Das ist
dieselbe Konsequenz wie in Entscheidung 1: Die Bibliothek bleibt der alleinige Rechteanker, ein Ordner ist
reine Navigation innerhalb dieses Ankers, kein eigener Anker.

Diese Entscheidung ist bewusst und dauerhaft — nicht nur „für den ersten Wurf". Eine feinere
Ordner-Berechtigung würde die zentrale Eigenschaft der rechtebewussten Suche aufweichen, dass jeder Chunk
genau eine Filterachse trägt (die Bibliotheks-Kennung, siehe `docs/CONCEPTS.md#wissensbibliothek`). Wer
Bestände mit unterschiedlichem Leserkreis braucht, legt getrennte Bibliotheken an — dafür existiert der
Mechanismus bereits.

### 4. Retrieval bleibt vorerst ohne Ordner-Filter

Die Suche filtert weiterhin nur nach Bibliotheks-Kennung, nicht nach Ordner. Ein Ordner-Filter bräuchte
einen neuen Chunk-Metadaten-Schlüssel (etwa `folder_id` neben der bestehenden Bibliotheks-Kennung) und
eine Re-Indexierung des Bestands, um ihn nachzutragen. Das ist als bewusste Ausbaustufe zurückgestellt,
nicht Teil dieses Epics — sie wäre ein separates Ticket, sobald ein konkreter Bedarf besteht (etwa: „nur
in diesem Unterordner suchen"). Bis dahin liefert die Suche innerhalb einer Bibliothek unverändert über
den gesamten Bestand, unabhängig von der Ordnerstruktur; die Trefferanzeige zeigt den Ordnerpfad zur
Einordnung an (siehe [`knowledge-sources.md`](../features/knowledge-sources.md)).

### 5. Löschen eines Ordners mit Inhalt löscht die enthaltenen Dokumente — nach Bestätigung, durch den Service

Löscht eine Person einen Ordner, der Dokumente (oder Unterordner mit Dokumenten) enthält, werden diese
Dokumente mitgelöscht — nicht in einen Wurzel- oder Papierkorbzustand verschoben. Die Oberfläche zeigt vor
der Löschung die Anzahl betroffener Dokumente und verlangt eine ausdrückliche Bestätigung.

**Kein Widerspruch zur Löschsperre auf Bibliotheksebene.** Für `UPLOAD`-Bibliotheken bleibt das Löschen
der gesamten Bibliothek blockiert, solange sie Dokumente enthält ([ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md),
Entscheidung 5) — diese Sperre schützt vor dem versehentlichen Wegwerfen eines ganzen kuratierten
Bestands in einer einzigen, schwer rückgängig zu machenden Aktion. Das Löschen eines einzelnen Ordners
mit Bestand ist demgegenüber ein gezielter Eingriff in einen klar umrissenen Teilbestand: Die Anzahl
betroffener Dokumente ist vor der Bestätigung sichtbar, die übrige Bibliothek bleibt unberührt, und die
Person hat den Ordnerinhalt beim Navigieren dorthin bereits gesehen. Diese geringere Schwelle ist deshalb
bewusst gesetzt, nicht ein Widerspruch zur strengeren Regel auf Bibliotheksebene.

**Der Löschvorgang läuft durch den Anwendungs-Service, nicht durch eine DB-Kaskade
(`ON DELETE CASCADE`).** Das Entfernen eines Dokuments ist mehr als eine Zeile: Es räumt Chunks im
Vektorspeicher und die abgelegte Datei im Dokumentenspeicher auf — dieselbe Aufräumlogik, die
`DELETE /api/v1/libraries/{libraryId}/documents/{documentId}` bereits für die Einzellöschung durchläuft
(siehe [`knowledge-sources.md`](../features/knowledge-sources.md)). Eine DB-Kaskade würde diese
Aufräumschritte überspringen und verwaiste Chunks bzw. Dateien hinterlassen. Unterordner löscht der
Service dagegen rekursiv mit derselben Logik.

### 6. Dedup bleibt bibliotheksweit für UPLOAD, pfadbasiert für lauf-basierte Typen — Ordner ändern daran nichts

Der bestehende Unique-Index `uk_documents_library_checksum` (Prüfsummengleichheit) ist partiell auf
`source_type = 'UPLOAD'` beschränkt (`WHERE checksum IS NOT NULL AND source_type = 'UPLOAD'`, siehe
`020-add-upload-metadata-to-documents.yaml`). Für `UPLOAD`-Bibliotheken gilt er weiterhin über die gesamte
Bibliothek, nicht je Ordner: Zwei identische Dateien in verschiedenen Ordnern derselben Bibliothek bleiben
ein Duplikat und werden abgewiesen — Ordner sind Navigation, keine Kopienverwaltung, und ändern nichts an
der fachlichen Frage „liegt dieser Inhalt schon in dieser Bibliothek".

Lauf-basierte Typen (`FILESYSTEM`, `HTTP_DIRECTORY`) fallen nicht unter diesen Index. Sie führen ihre
Dedup pfadbasiert über `file_path` (siehe `FileProcessingService#processFile`/`#processUrlFile`): Dieselbe
Datei in zwei Unterverzeichnissen einer `FILESYSTEM`-Quelle hat zwei unterschiedliche Pfade und ist damit
fachlich zwei legitime, unabhängige Dokumente — auch wenn ihr Inhalt und damit ihre Prüfsumme identisch
sind. Die Verzeichnisabbildung für `FILESYSTEM`-Bibliotheken (siehe Epic #520 Phase 4, [#824](https://github.com/criew/opaa/issues/824))
darf solche Dateien deshalb nicht als Duplikat zurückweisen; jede landet unverändert in ihrem jeweiligen
Ordner.

## Nachtrag (04.09.2026, #1277): Spiegelung auch für `HTTP_DIRECTORY`

Entscheidung 2 und die daraus folgende Spiegelung waren zunächst nur für `FILESYSTEM` umgesetzt
(#824). Ein gecrawltes Webverzeichnis hat dieselbe Eigenschaft, die die Spiegelung dort rechtfertigt:
Es ist ein Verzeichnisbaum, dessen Gliederung (Jahr, Referat, Vorgangstyp) fachlich gemeint ist und in
einer flachen Dokumentliste verloren geht — gleichnamige Dateien aus verschiedenen Unterverzeichnissen
sind dort nicht unterscheidbar. Die Spiegelung gilt deshalb ab #1277 auch für `HTTP_DIRECTORY`, mit
denselben Regeln und derselben Umsetzung (`materializeFolderPath`/`pruneOrphanedFolders`, ein
gemeinsamer Lauf-Helfer für beide Konnektoren statt einer zweiten Kopie).

**Der Ordnerpfad ist der URL-Pfad relativ zur normalisierten Start-URL**, segmentweise
prozentdekodiert (`Verg%C3%BCtung` → `Vergütung`). Query-Parameter und Fragment gehören nicht zum
Pfad. Ein Segment wird abgewiesen, wenn es nach der Dekodierung leer ist, `.` oder `..` lautet oder
einen Pfadtrenner (`/`, `\`) enthält — ein solcher Name lässt sich nicht als eine Ordnerzeile
darstellen, und `%2E%2E`/`%2F` überstehen die URL-Normalisierung des Crawlers, werden also erst hier
sichtbar. Die betroffene Datei landet dann in der Wurzel der Bibliothek, mit einer Warnung im
Anwendungsprotokoll — dieselbe Behandlung wie beim Dateisystem-Sonderfall einer Datei, die nach der
Normalisierung nicht unter `sourcePath` liegt. Sie wird nicht verworfen und nicht unter einem
erfundenen Namen abgelegt.

**Aufgeräumt wird nur nach einem vollständigen Lauf.** Ein durch ein Limit abgeschnittener
(`truncated`) oder ein Lauf, der ein Unterverzeichnis gar nicht abrufen konnte (`incomplete`), kennt
den Bestand der Quelle nicht vollständig; er darf so wenig einen Ordner entfernen wie ein Dokument.
Die Reihenfolge bleibt die von `FILESYSTEM`: erst die Bereinigung verschwundener Dokumente, dann die
der leeren Ordner, damit ein Ordner, dessen letztes Dokument gerade verschwunden ist, noch im selben
Lauf entfällt.

**Zuordnung ohne Neuindizierung.** Die Ordnerzuordnung geschieht im Konnektor, nachdem ein Eintrag
verarbeitet wurde — auch dann, wenn der Lauf ihn wegen unveränderter `Last-Modified`-Angabe gar nicht
erst heruntergeladen hat. Nur so bekommt ein vor #1277 indiziertes Dokument seine `folder_id`
nachgetragen, ohne erneut zerlegt und eingebettet zu werden.

**Anhänge folgen ihrer Elternmail** ([ADR-0022](0022-anhang-als-eigenes-dokument.md)): Der
Anhangsweg selbst bleibt unverändert, ein Anhang erbt lediglich die `folder_id` seines
Elterndokuments — sein eigener Pfad ist der Elternpfad plus Index, kein Verzeichnis. `RSS_FEED`
bleibt weiterhin ohne Ordner; ein Feed hat keine Struktur, die sich spiegeln ließe.

## Konsequenzen

### Einfacher

- **Leere Ordner sind darstellbar**, weil sie eine eigene Zeile sind statt eines abgeleiteten Zustands.
- **Umbenennen und Verschieben sind atomare Einzeiler-Updates**, unabhängig von der Zahl der enthaltenen
  Dokumente.
- **Der Rechteanker bleibt eindeutig.** Es gibt weiterhin genau eine Stelle, an der Zugriff entschieden
  wird — die Bibliothek —, keine zweite Prüfebene, die mit der ersten in Konflikt geraten könnte.
- **FILESYSTEM-Bibliotheken können ihre echte Verzeichnisstruktur direkt auf `library_folders` abbilden**
  (Ausbaustufe, siehe Epic #520 Phase 4), statt eine zweite Darstellung zu erfinden.

### Schwieriger

- **Löschen eines Ordners ist ein größerer Eingriff als das Löschen einer Zeile.** Die Service-Schicht
  muss rekursiv über Unterordner und deren Dokumente aufräumen (Chunks, Dateien), statt sich auf eine
  Datenbank-Kaskade zu verlassen — mehr Code, aber die einzige Stelle, die die bestehende
  Dokument-Löschlogik korrekt wiederverwendet.
- **Ordner-Filter im Retrieval bleibt eine offene Ausbaustufe.** Bis der Chunk-Metadaten-Schlüssel und die
  Re-Indexierung nachgezogen sind, findet „nur in diesem Ordner suchen" nicht statt; das ist eine bewusste
  Lücke, kein Versehen.

## Verworfene Alternativen

**Virtuelle Pfad-Präfixe statt eigener Ordner-Tabelle.** Siehe Entscheidung 2 — verworfen, weil leere
Ordner damit nicht darstellbar wären und Umbenennen zur String-Kaskade über alle betroffenen Dokumente
würde.

**Ordner-Berechtigungen als eigene Rechtegrenze.** Hätte es erlaubt, einzelne Ordner innerhalb einer
Bibliothek unterschiedlichen Leserkreisen zu geben. Verworfen, weil es die Filterachse der
rechtebewussten Suche verdoppelt hätte (Bibliothek und Ordner statt nur Bibliothek) und weil der
bestehende Mechanismus — getrennte Bibliotheken für getrennte Leserkreise — dasselbe Ergebnis ohne neue
Komplexität liefert. Wer Ordner-Rechte für einen konkreten Fall vermisst, legt eine eigene Bibliothek an;
siehe „Außerhalb des Umfangs" in Epic #520.
