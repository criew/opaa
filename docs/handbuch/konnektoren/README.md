# Konnektoren

Ein **Konnektor** bindet ein System an, in dem die Organisation Dokumente ablegt: ein
Netzlaufwerk, einen Cloud-Speicher, ein Wiki, einen Feed. Er weiß, wie dieses System aufgezählt und
gelesen wird; alles Weitere — Format erkennen, zerlegen, einbetten, durchsuchbar machen — ist für
jede Quelle gleich und steht im Kapitel [Wissensbibliotheken](../wissensbibliotheken.md). Wie sich ein Konnektor
beim System anmeldet und wie die Systemverwaltung das vorgibt, beschreibt das Kapitel
[Zugänge](../zugaenge.md).

## 1. Die Konnektoren im Überblick

**Tabelle 1.1: Was die Konnektoren lesen**

| Konnektor | Liest | Zugänge |
|---|---|---|
| [Verzeichnis im Dateisystem](filesystem.md) | ein Verzeichnis, das dem Backend als lokaler Pfad sichtbar ist, etwa ein eingebundenes Netzlaufwerk | keine |
| [Webverzeichnis](http-directory.md) | eine automatisch erzeugte Verzeichnisliste eines Webservers („Index of /…") | möglich |
| [Feed](rss-feed.md) | einen RSS-Feed, die verlinkten Detailseiten und deren Anlagen | möglich |
| [Confluence](confluence.md) | Seiten und Anhänge ausgewählter Spaces (Cloud oder Data Center) | möglich |
| [S3-Objektspeicher](s3.md) | Objekte in Buckets, wahlweise auf Präfixe eingegrenzt | möglich |
| [Google Drive](google-drive.md) | geteilte Ablagen, Ordner oder „Meine Ablage" über ein Dienstkonto | möglich, Schlüssel am Zugang |
| [SharePoint](sharepoint.md) | Dokumentbibliotheken von SharePoint Online | Pflicht |
| [Nextcloud](nextcloud.md) | Ordner eines Nextcloud-Nutzers, samt Freigaben und Gruppenordnern | möglich |
| [Dropbox](dropbox.md) | Ordner eines Dropbox-Kontos oder Team-Bereichs | Pflicht |
| [Windows-Dateifreigabe (SMB)](smb.md) | Ordner einer Freigabe auf einem Windows- oder Samba-Server | möglich |

**Tabelle 1.2: Wie die Konnektoren abgleichen**

| Konnektor | Betriebsarten | Entfernt Verschwundenes | Ordner in der Bibliothek |
|---|---|---|---|
| Verzeichnis im Dateisystem | vollständig | nach einem vollständigen Lauf | ja, aus der Verzeichnisstruktur |
| Webverzeichnis | vollständig | nach einem Crawl, der weder abgeschnitten noch unvollständig war | ja, aus dem URL-Pfad |
| Feed | ergänzend | nie | nein |
| Confluence | Vollabgleich, inkrementell, per Webhook | Vollabgleich über alle Spaces; Papierkorb und Umzug sofort | nein, Space und Gliederungspfad stehen am Dokument |
| S3-Objektspeicher | Vollabgleich, Ereignislauf | Vollabgleich über alle Geltungsbereiche; Ereignislauf nur auf Befund des Speichers | ja, aus den Schlüsselpräfixen |
| Google Drive | Vollabgleich, Änderungslauf | Vollabgleich in einem Lauf; Änderungslauf, wenn das Änderungsprotokoll die Löschung meldet | ja |
| SharePoint | Vollabgleich, Änderungslauf | Vollabgleich, über mehrere Läufe abgeschlossen durch das Änderungsprotokoll; Änderungslauf wie Google Drive | ja |
| Nextcloud | Vollabgleich | Vollabgleich, der alle Ordner in einem Lauf gelistet oder bestätigt hat | ja |
| Dropbox | Vollabgleich, Änderungslauf | Vollabgleich, auch über mehrere Läufe; Änderungslauf wie Google Drive | ja |
| Windows-Dateifreigabe (SMB) | Vollabgleich | Vollabgleich, auch über mehrere Läufe, sobald alle Ordner eines Bereichs gelistet sind | ja |

Ein *vollständiger* Lauf sieht die ganze Quelle und darf deshalb entfernen, was fehlt; ein
*ergänzender* sieht nur einen Ausschnitt — die jüngsten Feed-Einträge, die zuletzt geänderten
Seiten — und entfernt nie etwas, nur weil es fehlt. Die Kapitel der einzelnen Konnektoren
beschreiben Einrichtung, Schutzmechanismen, Ordner, Löscherkennung, Grenzwerte und Fehlerbilder.

## 2. Was jeder Konnektor liefert

Jeder Konnektor liefert dasselbe:

1. eine **Aufzählung** der Elemente, die die Quelle gerade enthält (Dateien, Adressen,
   Feed-Einträge, Seiten), samt der Aussage, ob sie **vollständig** ist. Nur eine vollständige
   Aufzählung darf dazu dienen, verschwundene Dokumente zu erkennen,
2. für jedes Element den **Inhalt**, als Datei oder als bereits gelesenen Text,
3. seine **Betriebsarten**: welche es gibt und ob sie vollständig auflisten oder nur ergänzen.

Zwei Dinge verantwortet der Konnektor selbst:

- **Schutz der Umgebung.** Ein Dateisystemkonnektor könnte beliebige Serverpfade lesen, ein
  Netzkonnektor auf interne Adressen umgeleitet werden oder unbegrenzt große Antworten laden. Jeder
  Konnektor hat deshalb eigene Freigabe- und Grenzmechanismen, die der Betrieb einstellt. Ein
  Verstoß beendet den Lauf oder das Element mit einem Protokolleintrag, nie still.
- **Unverändertes früh erkennen.** Oft sieht der Konnektor schon an der Aufzählung, dass sich ein
  Element nicht geändert hat — an einer Versionsnummer, einer Prüfsumme des Anbieters, einer
  bedingten HTTP-Anfrage — und spart sich den Download. Verbindlich entscheidet danach immer die
  eigene Prüfsumme.

Alles Übrige übernimmt ein **gemeinsamer Laufrahmen**: Zähler und Protokoll, die Zuordnung jedes
Elementergebnisses, die Buchführung über Anhänge, das Entfernen verschwundener Dokumente nach
vollständiger Aufzählung und verständliche Meldungen bei einem Abbruch. Deshalb lauten diese
Meldungen bei allen Konnektoren gleich, etwa „Lauf unterbrochen" oder „Die Bibliothek wurde während
des Laufs gelöscht.".

## 3. Abgleichstand und Einstellungen

Was ein Konnektor zwischen zwei Läufen festhält — eine Fortsetzungsstelle, einen Änderungszeiger,
den Anker eines inkrementellen Laufs, gemerkte Ordner — gilt nur für die **Einstellungen, unter
denen es entstand**: Adresse, Pfad und das, was der Konnektor als auswahlbestimmend vergleicht
(Ordner, Spaces, Geltungsbereiche, Filter, das imitierte Konto), dazu das Konto, als das die Quelle
verbunden ist. Vorgaben eines Zugangs zählen mit; Zertifikatsprüfung und Vollabgleich-Rhythmus
nicht.

- Ein Lauf ohne ausdrücklich gewählte Betriebsart (Zeitplan, „Jetzt indizieren", ein übergelaufener
  Ereignisstapel) ist ein Vollabgleich, sobald der gespeicherte Stand zu anderen Einstellungen
  gehört.
- Findet ein Lauf einen Stand anderer Einstellungen vor, verwirft er ihn, vermerkt das im Protokoll
  und gleicht vollständig neu ab. Ein ausdrücklich inkrementell gestarteter Lauf sucht dann nichts;
  der nächste Lauf ist ein Vollabgleich.
- Ändern sich die Einstellungen während eines Laufs, entfernt dieser Lauf nichts als verschwunden.
  Bei den Dateiablagen endet er meist als **fehlgeschlagen** mit „Die Quelle der Bibliothek wurde
  während des Laufs geändert …", bei Confluence als **unvollständig**. Der nächste Lauf gleicht
  unter den neuen Einstellungen ab.

## 4. Dateiablagen

S3, Google Drive, SharePoint, Nextcloud, Dropbox und die Windows-Dateifreigabe teilen sich einen
gemeinsamen Abgleich: wie ein Vollabgleich über mehrere Läufe fortgesetzt wird, wann eine
gelöschte Datei als nachgewiesen gilt und wie das Speicherkontingent schon vor dem Download greift.
Das beschreibt das Kapitel [Abgleich der Dateiablagen](datei-abgleich.md).

## Tabellenverzeichnis

- [Tabelle 1.1: Was die Konnektoren lesen](#1-die-konnektoren-im-überblick)
- [Tabelle 1.2: Wie die Konnektoren abgleichen](#1-die-konnektoren-im-überblick)
