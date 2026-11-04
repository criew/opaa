# ADR-0068: Mitbestimmungsfähigkeit als Produkteigenschaft

## Status

Akzeptiert · zuletzt geändert 2026-11-02

## Kontext

OPAA erzeugt Daten mit Personenbezug: Chatverläufe, Protokolldaten, künftig Nutzungsangaben je Asset.
In einer Behörde ist das mitbestimmungspflichtig. Ohne Dienstvereinbarung beginnt in der Regel kein
Rollout, und die Personalvertretung fragt genau nach diesen Daten. Das ist das am häufigsten
unterschätzte Einführungshindernis.

Die Vorlagen für Personalrat und Dienstvereinbarung liegen unter
[`docs/behoerden/05-mitbestimmung/`](../behoerden/05-mitbestimmung/README.md).

## Entscheidung

### 1. Sichtbarkeit ist eine Handlung

Chats entstehen privat. Für andere sichtbar wird nur, was eine Person bewusst teilt. Private Inhalte
sind gegenüber Systemverwaltung, Revision und Leitung unbeobachtet und fachlich gleichwertig: Ein
privater Chat durchsucht denselben Bereich wie ein geteilter im selben Space. Geschützt ist der
Inhalt, nicht die Tatsache der Nutzung. Fragen, Suchbegriffe und Chatinhalte stehen weder im
Revisionsprotokoll noch im Anwendungslog; die einzelne Nutzung protokolliert OPAA nicht
([ADR-0059](0059-ereignisvertrag-aufbewahrung-und-integritaet-des-revisionsprotokolls.md), Entscheidung 1).

### 2. Kein personenbezogener Auswertungspfad

Keine Schnittstelle und keine Oberfläche filtert, gruppiert oder sortiert Nutzungs-, Chat- oder
Herkunftsdaten nach Person. Die Funktion ist nicht abgeschaltet, sondern nicht gebaut. Was es nicht
gibt, kann niemand einschalten, auch nicht rückwirkend über die Daten von gestern.

Zwei Wege führen zur Person. Die
[Selbstauskunft](../handbuch/benutzer/meine-daten.md)
steht nur der Person selbst offen; sie ist die Identität der Anfrage, kein Filter. Für Dritte gibt es
genau einen Weg, die anlassbezogene Klärung eines Sicherheitsvorfalls
([Berechtigungen](../handbuch/referenz/rechte-matrix.md)): Person, Zeitraum und Zweck werden
vorab festgelegt und begrenzen die Abfrage technisch, eine zweite Person der Revision gibt sie frei.
Arbeitsrechtliche, disziplinarische und leistungsbezogene Zwecke sind nicht wählbar, und die Fälle
zählt OPAA ohne Namen für den Bericht an die Personalvertretung. Ein Produkt ohne Aufklärung eines
Vorfalls wäre nicht betreibbar, eines mit Aufklärung als Normalweg nicht zustimmungsfähig.

### 3. Keine Ranglisten

Es gibt keinen Vergleich einzelner Beschäftigter, auch nicht als spielerisches Element. Statistiken
sind aggregiert. Unterhalb einer Mindestgruppengröße wird ein Wert unterdrückt, nicht gerundet. Das
Produkt setzt eine Untergrenze von fünf, die sich nur erhöhen lässt
([ADR-0036](0036-berechtigungsmodell-gruppen-und-faehigkeiten.md)).

### 4. Begrenzte Aufbewahrung

Protokolldaten liegen 12 bis 120 Monate, in diesen Grenzen einstellbar, und werden danach gelöscht
([ADR-0059](0059-ereignisvertrag-aufbewahrung-und-integritaet-des-revisionsprotokolls.md),
Entscheidung 2). Chats löscht OPAA, wenn ein Space die Bereinigung einschaltet oder ein Konto gesperrt
wurde ([ADR-0066](0066-chatliste-ablage-suche-und-bereinigung.md), Entscheidungen 7 und 8). Die
Netzadresse gehört nicht in den Protokollsatz, weil sie Dienststelle und Heimarbeit unterscheidbar macht.

### 5. Getrennte Zugriffswege, technisch durchgesetzt

Revision (`AUDITOR`) und Verwaltung haben verschiedene Wege zu den Daten. Die Trennung steckt in Rollen
und Schnittstellen, nicht in einer organisatorischen Zusage. Für jede erhobene Angabe ist der Zweck im
Fakten-Manifest ([ADR-0052](0052-compliance-manifest.md)) dokumentiert. Daraus entsteht der Auszug für
die Personalvertretung.

## Konsequenzen

- Einfacher: Die Dienstvereinbarung wird zur Konfigurationsaufgabe. Verhandelt werden Fristen, Schwellen
  und Zuständigkeiten, nicht die Frage, ob es eine Überwachungsfunktion gibt.
- Schwieriger: Jede neue Funktion mit Personenbezug wird an diesen fünf Regeln gemessen und mit
  `data-impact` gekennzeichnet.
- Nicht regelbar im Produkt: Freiwilligkeit der Nutzung, die passende Mindestgruppengröße für die
  Einheiten des Hauses und die rechtliche Bewertung. Das entscheidet die einführende Stelle.

## Verworfene Alternativen

- Personenbezogene Auswertung, ab Werk abgeschaltet: Eine Zusage statt einer Tatsache, und morgen
  einschaltbar mit rückwirkend auswertbaren Daten.
- Ranglisten als spielerisches Element: Der Rahmen ändert nichts an der Datengrundlage.
- Netzadresse im Standard-Protokollsatz: Sie ist ein Anwesenheitsmerkmal.

## Offen

- Teilen von Chats in den Space, Nutzungsangaben je Asset und eine allgemeine Höchstdauer für Chats
  stehen in der [Roadmap](../ROADMAP.md).
