# Unterlagen für Behörden

> Die Fassung im Repository ist der Entwicklungsstand. Für eine Installation gelten die Vorlagen im
> Update-Paket der eingesetzten Version, siehe [Wo man die Vorlagen liest](#wo-man-die-vorlagen-liest).

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
| `00-entscheidung/` | [Entscheidung der Hausleitung](00-entscheidung/README.md) |
| `01-markterkundung/` | [Markterkundung](01-markterkundung/README.md) |
| `02-pilot-teststellung/` | Pilot und Teststellung (folgt) |
| `03-vergabe/` | [Vergabe](03-vergabe/README.md) |
| `04-datenschutz/` | [Datenschutz](04-datenschutz/README.md) |
| `05-mitbestimmung/` | [Mitbestimmung](05-mitbestimmung/README.md) |
| `06-informationssicherheit/` | [Informationssicherheit, Cyber Resilience Act](06-informationssicherheit/README.md) |
| `07-ki-verordnung/` | [KI-Verordnung](07-ki-verordnung/README.md) |
| `08-nutzung/` | [Barrierefreiheit und Nutzungsrichtlinie](08-nutzung/README.md) |
| `09-betrieb-update/` | [Notfall, Wiederherstellung und Updates](09-betrieb-update/README.md) |

Jede Vorlage ist gleich aufgebaut: für wen sie gilt, worum es geht, was OPAA liefert,
welche Aufgaben bei der Behörde liegen und am Ende Arbeitshilfen und Rechtsgrundlagen zum Nachlesen. Oben in jeder
Datei steht, wann sie zuletzt durchgesehen wurde, auf welchem Rechtsstand sie beruht und dass sie
nicht rechtlich geprüft ist.

## Wo man die Vorlagen liest

Die Vorlagen gehen davon aus, dass sie im
[Update-Paket](../handbuch/betrieb/releases-und-nachweise.md#das-update-paket) der eingesetzten
Version gelesen werden. Es hängt an der GitHub-Seite der Version (Releases) als
`opaa-update-paket-X.Y.Z.tar.gz`. Nach dem Entpacken öffnet `index.html` alles im Browser, auch ohne
Internetzugang: die Vorlagen, das Handbuch und die Faktenblätter dieser Version, untereinander
verlinkt. Daneben liegt jede Datei als Markdown zum Weiterbearbeiten.

Die Faktenblätter werden aus dem Programmcode erzeugt: welche Daten gespeichert werden, wer was
sieht, was protokolliert wird, welche Daten das Haus verlassen, welche Fristen und Voreinstellungen
gelten. Sie stimmen deshalb immer mit der Version überein, und die Vorlagen nennen keine Werte
selbst, sondern verweisen auf sie. Im Repository stehen an diesen Stellen Platzhalter wie
`{{faktenblatt:datenkatalog}}`, im Paket Links.

