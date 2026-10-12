# Konnektoren

Das meiste Wissen einer Organisation liegt schon irgendwo: auf einem Netzlaufwerk, in einem
Cloud-Speicher, in einem Wiki, in einem Feed. Ein **Konnektor** bindet ein solches System an. Er
weiß, wie es aufgezählt und gelesen wird; alles Weitere — Format erkennen, zerlegen, einbetten,
durchsuchbar machen — ist für jede Quelle gleich und steht im Kapitel
[Wissensbibliotheken](../wissensbibliotheken.md). Wie sich ein Konnektor beim System anmeldet und
was die Systemverwaltung dafür vorgibt, beschreibt das Kapitel [Zugänge](../zugaenge.md).

Die Konnektoren sind verschieden, folgen aber wenigen gemeinsamen Mustern: Jeder liefert dasselbe
an die Aufnahmestrecke (Abschnitt 1), und die Dateiablagen gleichen ihre Quelle auf demselben Weg
ab (Abschnitt 2). Was jeder einzelne liest und wie er abgleicht, zeigt Abschnitt 3; die Kapitel der
Konnektoren beschreiben Einrichtung, Schutzmechanismen, Ordner, Löscherkennung, Grenzwerte und
Fehlerbilder.

## 1. Was jeder Konnektor leistet

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

### 1.1 Abgleichstand und Einstellungen

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

## 2. Dateiablagen

S3, Google Drive, SharePoint, Nextcloud, Dropbox und die Windows-Dateifreigabe (SMB) gleichen ihre
Quelle auf demselben Weg ab. Die Eigenheiten jeder Ablage stehen in ihrem Kapitel.

### 2.1 Vollabgleich und Änderungslauf

Ein **Vollabgleich** listet alle konfigurierten Bereiche — Buckets, Ablagen, Dokumentbibliotheken,
Ordner — und vergleicht sie mit dem Bestand der Bibliothek. Vor jedem Download prüft er ein
Änderungsmerkmal der Ablage (eine Prüfsumme, eine Version, Änderungszeit und Größe); stimmt es,
wird die Datei nicht geladen.

Google Drive, SharePoint und Dropbox führen zusätzlich ein **Änderungsprotokoll**. Zwischen zwei
Vollabgleichen liest ein **Änderungslauf** nur dieses Protokoll ab dem zuletzt gemerkten Stand und
lädt nur, was sich geändert hat. Der Vollabgleich merkt sich diesen Stand **vor** seiner
Auflistung, damit nichts verloren geht, was sich währenddessen ändert. S3, Nextcloud und SMB kennen
nur den Vollabgleich; Nextcloud spart sich dabei unveränderte Ordner.

| Ablage | Vollabgleich | Änderungslauf |
|---|---|---|
| S3 | ja | nein; Ereignisse des Speichers lösen einen Ereignislauf aus |
| Google Drive | ja | ja |
| SharePoint | ja | ja |
| Nextcloud | ja, nur in geänderte Ordner | nein |
| Dropbox | ja | ja |
| Windows-Dateifreigabe (SMB) | ja | nein |

### 2.2 Ein Vollabgleich über mehrere Läufe

Jeder Lauf hat ein **Anfragebudget**. Reicht es nicht für die ganze Auflistung, endet der Lauf
geordnet als „unvollständig, wird fortgesetzt", und der nächste Lauf setzt fort. So wird auch eine
Ablage, die größer ist als ein Lauf, nach einigen Läufen vollständig abgeglichen. Wo der nächste
Lauf ansetzt, hängt von der Ablage ab: an der gemerkten Stelle der Auflistung (Nextcloud, SMB,
SharePoint), bei den noch nicht gelisteten Ordnern (Dropbox) oder mit einer erneuten Auflistung
aller Bereiche, die nur die schon geladenen Dateien überspringt (S3).

### 2.3 Wann eine gelöschte Datei als nachgewiesen gilt

Ein Dokument wird nur entfernt, wenn feststeht, dass seine Datei nicht mehr da ist. Ein
Vollabgleich, der in **einem** Lauf alles gelistet hat, weist das für jede Ablage nach. Erstreckt er
sich über mehrere Läufe, kann eine Datei zwischen zwei Läufen aus einem noch nicht gelisteten in
einen schon gelisteten Bereich gewandert sein. Ob er dann trotzdem entfernen darf, hängt von der
Ablage ab:

| Nachweis | Ablagen | Regel |
|---|---|---|
| nur in einem Lauf | S3, Google Drive, Nextcloud | Ein Abgleich über mehrere Läufe entfernt nichts. Er merkt sich aber, was er gesehen hat; der nächste Lauf passt dann meist in ein Budget und entfernt, was fehlt |
| Ort ist Identität | SMB, Dropbox | Der Pfad ist die Identität des Dokuments. Eine Datei, die an keinem gelisteten Pfad vorkam, gibt es nicht mehr; der Abgleich entfernt, was keiner seiner Läufe gesehen hat |
| Änderungsprotokoll | SharePoint | Am Ende des Abgleichs liest OPAA das Änderungsprotokoll ab dem Stand, der zu seinem Beginn galt. Nur wenn es vollständig gelesen wurde, entfernt der Abgleich, was weder seine Läufe noch das Protokoll gesehen haben |

Ein Lauf, der einen Bereich nicht lesen kann oder null Dateien sieht, entfernt dort nichts. Ein
entzogenes Recht ist kein Löschbefund.

### 2.4 Speicherkontingent vor dem Download

Eine Dateiablage befragt das Speicherkontingent schon **vor dem Download**, mit der Größe aus der
Auflistung. Kündigt sie gegenüber dem gespeicherten Stand Wachstum an, das das Kontingent der
Bibliothek oder — bei einer privaten Bibliothek — das ihrer Besitzerin überschreitet, wird die Datei
gar nicht erst geladen und genauso abgelehnt wie nach dem Download: derselbe Protokolleintrag,
dieselbe Kategorie `QUOTA_EXHAUSTED`, die Datei gilt als vorhanden. Ohne bekannte Größe und bei
gleicher oder kleinerer Größe wird geladen.

Die Vorprüfung ist nur beratend; maßgeblich bleibt die Prüfung mit der tatsächlichen Größe vor dem
Speichern. Weil eine falsche Listengröße eine Datei auch zu Unrecht zurückhalten kann, hält jede
Ablehnung an einem Kontingent, auch die eines Anhangs, Änderungsstand und Ordnergedächtnis der
Ablage fest. Sobald Platz für die gelistete Größe frei ist, wird die Datei geladen und mit ihrer
echten Größe gezählt.

Wann eine abgelehnte Datei erneut geprüft wird:

- Bei S3, Nextcloud und SMB prüft sie der nächste Vollabgleich.
- Bei Google Drive, SharePoint und Dropbox prüft eine Datei, die das Änderungsprotokoll meldet,
  schon der nächste Änderungslauf. Eine Datei, die in der Auflistung eines Vollabgleichs abgelehnt
  wurde, meldet das Protokoll danach nicht wieder. Deshalb ist der nächste Lauf nach einem solchen
  Vollabgleich wieder ein Vollabgleich. Solange die Bibliothek voll bleibt, tritt so an die Stelle
  jedes Änderungslaufs ein Vollabgleich; unveränderte Dateien lädt auch er nicht erneut.
- Ein Vollabgleich über mehrere Läufe endet trotz einer Ablehnung, und die Löschungen, die das
  Änderungsprotokoll meldet, werden übernommen. Wurde die Datei erst beim Lesen des Protokolls am
  Ende der Runde abgelehnt, liest der nächste Änderungslauf das Protokoll ab dem Stand, an dem der
  Vollabgleich begann, und trifft sie erneut.

## 3. Die Konnektoren im Überblick

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
Seiten — und entfernt nie etwas, nur weil es fehlt.
