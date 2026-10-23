---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Wiederherstellungsprobe

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** alle Behörden, die OPAA selbst betreiben oder betreiben lassen.

Eine Sicherung, deren Wiederherstellung nie geprüft wurde, ist keine Sicherung. Die
Wiederherstellungsprobe spielt eine Sicherung in eine leere Testinstallation zurück und zeigt, ob
Datenbank, Suchindex und Originale zueinander passen und ob Rechte erhalten bleiben. Sie misst die
Werte, die das [Notfallkonzept](notfallkonzept.md) festlegt; die Behörde veranlasst sie, der
Betrieb führt sie durch.

> **Beispiel.** Ein Landratsamt lässt seinen Dienstleister die Probe einmal im Jahr fahren, mit
> einer Kopie des Bestands auf einem Testsystem. Die Projektleitung bekommt das ausgefüllte
> Protokoll: Alle Prüfungen bestanden, Wiederherstellung in drei Stunden, verloren ist nur, was
> nach der Sicherung kam. Sie trägt Datum und Dauer ins Notfallkonzept ein und legt das Protokoll
> zum Sicherheitskonzept.

## Was OPAA liefert

- **Das Verfahren** mit Testbestand, Ablauf, zwölf Prüfungen (P1 bis P12) und Messung:
  [Wiederherstellungsprobe](../../handbuch/betrieb.md#wiederherstellungsprobe) im Handbuch, die
  Befehle für einen Testcluster unter [Wiederherstellungsprobe auf einem Testcluster](../../handbuch/kubernetes.md#wiederherstellungsprobe-auf-einem-testcluster).
- <a id="automatisierte-probe-im-projekt"></a>**Eine automatisierte Probe im Projekt:** Der Workflow
  [`helm-chart.yml`](../../../.github/workflows/helm-chart.yml) führt sie nächtlich auf einem
  Testcluster aus ([Im Projekt geprüft](../../handbuch/kubernetes.md#sicherung-und-wiederherstellung)).
  Sie belegt, dass das Verfahren des Handbuchs funktioniert; P5, P7 und P8 brauchen Modelle und
  bleiben der eigenen Probe. Die Zeiten dort stammen aus einem kleinen Testbestand und sagen nichts
  über die eigene Installation.

## Aufgaben der Behörde

1. **Probe beauftragen**, beim eigenen Betrieb oder beim Dienstleister, und den Turnus festlegen.
2. **Echtdaten oder Testbestand entscheiden:** Nur eine Kopie des Produktivbestands belegt die
   Wiederherstellungszeit. Sie wird dann wie Produktivdaten behandelt und danach gelöscht.
3. **Protokoll entgegennehmen** und prüfen, ob alle Prüfungen bestanden sind.
4. **Ergebnis ins Notfallkonzept übernehmen**, Abweichungen als Maßnahme verfolgen.
5. **Wiederholen** nach Plan, nach jedem Update mit Vorbereitungsschritten und nach jeder Änderung am
   Aufbau der Installation.

## Ergebnis festhalten

```text
Datum der Probe:              [ ]
OPAA-Version, Image-Digests:  [ ]
Betriebsweg und Ablage:       [Compose / Kubernetes; Dateisystem / Objektspeicher]
Bestand:                      [Testbestand / Kopie des Produktivbestands], Größe Datenbank [ ],
                              Menge der Originale [ ], Zahl der Dokumente [ ]
Wiederherstellungszeit (Ist): [ ]   Zielwert laut Notfallkonzept: [ ]
Wiederherstellungspunkt (Ist):[ ]   Zielwert laut Notfallkonzept: [ ]
Prüfungen P1 bis P12:         [alle bestanden / nicht bestanden: ...]
Abweichungen, Maßnahmen:      [ ]
Durchgeführt von:             [ ]        abgenommen von: [ ]        am: [ ]
```

## Gut zu wissen

- Die gemessene Zeit umfasst nur die Technik. Im Ernstfall kommen Erkennung, Entscheidung und
  gegebenenfalls Beschaffung von Hardware hinzu.
- Zeigt die Probe eine Abweichung vom Handbuch, ist das ein Fehler in Dokumentation oder Produkt;
  bitte im Projekt melden, eine Sicherheitsfrage über den [Meldeweg](../../../SECURITY.md#meldeweg).

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Grundschutz-Modellierung](../06-informationssicherheit/grundschutz-modellierung.md), Bausteine
  CON.3 Datensicherungskonzept und DER.4 Notfallmanagement: Dort gehört das Protokoll hin.
- [Notfallwiederherstellung](../../features/deployment-infrastructure.md#notfallwiederherstellung):
  was ein Wiederherstellungstest umfassen muss, aus der Produktspezifikation.
