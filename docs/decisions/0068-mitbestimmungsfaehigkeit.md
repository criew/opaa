# ADR-0068: Mitbestimmungsfähigkeit als Produkteigenschaft

## Status

Akzeptiert · zuletzt geändert 2026-11-02

## Kontext

OPAA erzeugt Daten mit Personenbezug: Chatverläufe, Protokolldaten, künftig Nutzungsangaben je Asset.
In einer Behörde ist das mitbestimmungspflichtig. Ohne Dienstvereinbarung beginnt in der Regel kein
Rollout, und die Personalvertretung fragt genau nach diesen Daten. Das ist das am häufigsten
unterschätzte Einführungshindernis.

Die Regeln standen bis November 2026 in der Spezifikation „Sicherheit, Nachweis & Prüfbarkeit“. Die
Vorlagen für Personalrat und Dienstvereinbarung liegen unter
[`docs/behoerden/05-mitbestimmung/`](../behoerden/05-mitbestimmung/README.md).

## Entscheidung

### 1. Sichtbarkeit ist eine Handlung

Chats entstehen privat. Für andere sichtbar wird nur, was eine Person bewusst teilt. Private Inhalte
sind gegenüber Systemverwaltung, Revision und Leitung unbeobachtet und fachlich gleichwertig: Ein
privater Chat durchsucht denselben Bereich wie ein geteilter im selben Space. Geschützt ist der
Inhalt, nicht die Tatsache der Nutzung. Dass jemand arbeitet, kann im Protokoll stehen, was er
schreibt, nicht.

### 2. Kein personenbezogener Auswertungspfad

Keine Schnittstelle und keine Oberfläche filtert, gruppiert oder sortiert Nutzungs-, Chat- oder
Herkunftsdaten nach Person. Die Funktion ist nicht abgeschaltet, sondern nicht gebaut. Was es nicht
gibt, kann niemand einschalten, auch nicht rückwirkend über die Daten von gestern. Einzige Ausnahme
ist die [Selbstauskunft](../handbuch/betrieb/nachvollziehbarkeit.md): Die Person ist dort die
Identität der Anfrage, kein Filter, den jemand anderes setzen könnte.

### 3. Keine Ranglisten

Es gibt keinen Vergleich einzelner Beschäftigter, auch nicht als spielerisches Element. Statistiken
sind aggregiert. Unterhalb einer Mindestgruppengröße wird ein Wert unterdrückt, nicht gerundet. Die
Voreinstellung setzt das Produkt, die Höhe gehört in die Dienstvereinbarung.

### 4. Aufbewahrung mit Höchstdauer

Chats und Protokolldaten haben eine einstellbare Frist mit Ober- und Untergrenze und werden nach Ablauf
gelöscht ([ADR-0059](0059-ereignisvertrag-aufbewahrung-und-integritaet-des-revisionsprotokolls.md),
[ADR-0066](0066-chatliste-ablage-suche-und-bereinigung.md)). Die Netzadresse gehört nicht in den
Protokollsatz, weil sie Dienststelle und Heimarbeit unterscheidbar macht.

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
- Nicht regelbar im Produkt: Freiwilligkeit der Nutzung, die Höhe der Mindestgruppengröße und die
  rechtliche Bewertung. Das entscheidet die einführende Stelle.

## Verworfene Alternativen

- Personenbezogene Auswertung, ab Werk abgeschaltet: Eine Zusage statt einer Tatsache, und morgen
  einschaltbar mit rückwirkend auswertbaren Daten.
- Ranglisten als spielerisches Element: Der Rahmen ändert nichts an der Datengrundlage.
- Netzadresse im Standard-Protokollsatz: Sie ist ein Anwesenheitsmerkmal.

## Offen

- Teilen von Chats in den Space, Nutzungsangaben je Asset mit Mindestgruppengröße und die
  anlassbezogene Klärung eines Sicherheitsvorfalls im Vier-Augen-Prinzip stehen in der
  [Roadmap](../ROADMAP.md).
