# Abgleich der Dateiablagen

S3, Google Drive, SharePoint, Nextcloud, Dropbox und die Windows-Dateifreigabe (SMB) gleichen ihre
Quelle auf demselben Weg ab. Dieses Kapitel beschreibt, was für alle gilt; die Eigenheiten jeder
Ablage stehen in ihrem Kapitel (Übersicht: [Konnektoren](README.md)).

## 1. Vollabgleich und Änderungslauf

Ein **Vollabgleich** listet alle konfigurierten Bereiche — Buckets, Ablagen, Dokumentbibliotheken,
Ordner — und vergleicht sie mit dem Bestand der Bibliothek. Vor jedem Download prüft er ein
Änderungsmerkmal der Ablage (eine Prüfsumme, eine Version, Änderungszeit und Größe); stimmt es,
wird die Datei nicht geladen.

Google Drive, SharePoint und Dropbox führen zusätzlich ein **Änderungsprotokoll**. Zwischen zwei
Vollabgleichen liest ein **Änderungslauf** nur dieses Protokoll ab dem zuletzt gemerkten Stand und
lädt nur, was sich geändert hat. Der Vollabgleich merkt sich diesen Stand **vor** seiner
Auflistung, damit nichts verloren geht, was sich währenddessen ändert. S3, Nextcloud und SMB kennen
nur den Vollabgleich; Nextcloud spart sich dabei unveränderte Ordner.

**Tabelle 1.1: Laufarten der Dateiablagen**

| Ablage | Vollabgleich | Änderungslauf |
|---|---|---|
| S3 | ja | nein; Ereignisse des Speichers lösen einen Ereignislauf aus |
| Google Drive | ja | ja |
| SharePoint | ja | ja |
| Nextcloud | ja, nur in geänderte Ordner | nein |
| Dropbox | ja | ja |
| Windows-Dateifreigabe (SMB) | ja | nein |

## 2. Ein Vollabgleich über mehrere Läufe

Jeder Lauf hat ein **Anfragebudget**. Reicht es nicht für die ganze Auflistung, endet der Lauf
geordnet als „unvollständig, wird fortgesetzt", und der nächste Lauf setzt fort. So wird auch eine
Ablage, die größer ist als ein Lauf, nach einigen Läufen vollständig abgeglichen. Wo der nächste
Lauf ansetzt, hängt von der Ablage ab: an der gemerkten Stelle der Auflistung (Nextcloud, SMB,
SharePoint), bei den noch nicht gelisteten Ordnern (Dropbox) oder mit einer erneuten Auflistung
aller Bereiche, die nur die schon geladenen Dateien überspringt (S3).

## 3. Wann eine gelöschte Datei als nachgewiesen gilt

Ein Dokument wird nur entfernt, wenn feststeht, dass seine Datei nicht mehr da ist. Ein
Vollabgleich, der in **einem** Lauf alles gelistet hat, weist das für jede Ablage nach. Erstreckt er
sich über mehrere Läufe, kann eine Datei zwischen zwei Läufen aus einem noch nicht gelisteten in
einen schon gelisteten Bereich gewandert sein. Ob er dann trotzdem entfernen darf, hängt von der
Ablage ab:

**Tabelle 3.1: Löschnachweis eines Vollabgleichs über mehrere Läufe**

| Nachweis | Ablagen | Regel |
|---|---|---|
| nur in einem Lauf | S3, Google Drive, Nextcloud | Ein Abgleich über mehrere Läufe entfernt nichts. Er merkt sich aber, was er gesehen hat; der nächste Lauf passt dann meist in ein Budget und entfernt, was fehlt |
| Ort ist Identität | SMB, Dropbox | Der Pfad ist die Identität des Dokuments. Eine Datei, die an keinem gelisteten Pfad vorkam, gibt es nicht mehr; der Abgleich entfernt, was keiner seiner Läufe gesehen hat |
| Änderungsprotokoll | SharePoint | Am Ende des Abgleichs liest OPAA das Änderungsprotokoll ab dem Stand, der zu seinem Beginn galt. Nur wenn es vollständig gelesen wurde, entfernt der Abgleich, was weder seine Läufe noch das Protokoll gesehen haben |

Ein Lauf, der einen Bereich nicht lesen kann oder null Dateien sieht, entfernt dort nichts. Ein
entzogenes Recht ist kein Löschbefund.

## 4. Speicherkontingent vor dem Download

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

## Tabellenverzeichnis

- [Tabelle 1.1: Laufarten der Dateiablagen](#1-vollabgleich-und-änderungslauf)
- [Tabelle 3.1: Löschnachweis eines Vollabgleichs über mehrere Läufe](#3-wann-eine-gelöschte-datei-als-nachgewiesen-gilt)
