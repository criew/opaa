# Unterlagen für Behörden

Dieser Ordner enthält Vorlagen für die Einführung und den Betrieb von OPAA in einer Behörde, geordnet
nach dem Einführungsweg.

## Zweck

Die Vorlagen sind eine Hilfe, kein Ersatz. Sie erklären kurz, worum es geht, was OPAA beisteuert und
welche Aufgaben bei der Behörde liegen.

OPAA liefert die Fakten, die Behörde bewertet sie. Die Vorlagen sind keine Rechtsberatung.

Das Produkt selbst beschreibt das [Handbuch](../handbuch/README.md).

## Was hier „Behörde“ heißt

Gemeint sind Stellen von Bund, Ländern und Kommunen sowie Körperschaften und Anstalten des
öffentlichen Rechts, etwa ein Landratsamt, eine Hochschule oder ein Bundesamt. Unternehmen sind nicht
Zielgruppe.

Die Grenze verläuft nicht in jedem Thema gleich. Eine kommunale GmbH muss etwa ausschreiben wie eine
Behörde, hat aber einen Betriebsrat statt eines Personalrats. Jede Vorlage nennt deshalb unter dem
Titel, für wen sie gilt. Bei Mischformen klärt das die Rechtsabteilung.

## Aufbau

| Verzeichnis | Phase |
|---|---|
| `00-entscheidung/` | Entscheidung der Hausleitung (folgt) |
| `01-markterkundung/` | Markterkundung (folgt) |
| `02-pilot-teststellung/` | Pilot und Teststellung (folgt) |
| [`03-vergabe/`](03-vergabe/) | Vergabe |
| [`04-datenschutz/`](04-datenschutz/) | Datenschutz |
| `05-mitbestimmung/` | Mitbestimmung (folgt) |
| [`06-informationssicherheit/`](06-informationssicherheit/) | Informationssicherheit, Cyber Resilience Act |
| [`07-ki-verordnung/`](07-ki-verordnung/) | KI-Verordnung |
| [`08-nutzung/`](08-nutzung/) | Barrierefreiheit und Nutzungsrichtlinie |
| [`09-betrieb-update/`](09-betrieb-update/) | Notfall, Wiederherstellung und Updates |

Jede Vorlage ist gleich aufgebaut: für wen sie gilt, worum es geht, ein Beispiel, was OPAA liefert,
welche Aufgaben bei der Behörde liegen und am Ende Arbeitshilfen und Rechtsgrundlagen zum Nachlesen. Oben in jeder
Datei steht, wann sie zuletzt durchgesehen wurde, auf welchem Rechtsstand sie beruht und dass sie
nicht rechtlich geprüft ist.

## Faktenblätter

Verweise wie {{faktenblatt:datenkatalog}} zeigen auf die Faktenblätter. Das sind Übersichten, die
aus dem Programmcode erzeugt werden: welche Daten gespeichert werden, wer was sieht, was protokolliert
wird, welche Daten das Haus verlassen, welche Fristen und Voreinstellungen gelten. Weil sie erzeugt
werden, stimmen sie immer mit der Version überein.

Die Vorlagen nennen deshalb keine Werte selbst. Ein Verweis wie {{faktenblatt:datenkatalog}} steht
für die Datei `datenkatalog.md` der Version, die die Behörde einsetzt. Diese Dateien liegen im
[Update-Paket](../releases.md#update-paket): Auf der GitHub-Seite der Version (Releases) die Datei
`opaa-update-paket-X.Y.Z.tar.gz` herunterladen und entpacken, die Faktenblätter stehen im Ordner
`faktenblaetter/`.

Wer Vorlagen schreibt oder ändert, findet die Regeln in [AGENTS.md](AGENTS.md).
