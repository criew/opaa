---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# C5 für den Cloud-Betrieb

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** nur Behörden, die OPAA als Cloud-Dienst betreiben oder von einem Cloud-Anbieter
betreiben lassen. Wer OPAA im eigenen Rechenzentrum betreibt, braucht C5 nicht und folgt dem
[IT-Grundschutz](grundschutz-modellierung.md).

C5 ist ein Kriterienkatalog des BSI, mit dem eine unabhängige Prüfstelle den Betrieb eines
Cloud-Dienstes prüft und ein *Testat* (Prüfbescheinigung) ausstellt. Geprüft werden Organisation,
Personal und Verfahren des Anbieters, nicht eine Software: **OPAA selbst ist nicht C5-testiert und
wird es nie.** Das Testat erhält der Anbieter, der OPAA betreibt.

## Was OPAA liefert

OPAA ist so gebaut und dokumentiert, dass ein Anbieter die Prüfung mit OPAA im Prüfumfang bestehen
kann.
Für Identitäten, Regelbetrieb, Kryptographie, Netz, Entwicklung, Sicherheitsvorfälle und
Notfallvorsorge kann er das [Handbuch](../../handbuch/betrieb.md), die
[Sicherheitsnachweise](sicherheitsnachweise.md) und die Faktenblätter vorlegen, etwa
{{faktenblatt:datenfluesse}} und {{faktenblatt:rollensicht}}. Kein Prüfbereich ist durch OPAA allein
erfüllt.

## Aufgaben der Behörde

1. **Testat erfragen:** Fassung, Typ 1 oder Typ 2 (Prüfung zu einem Stichtag oder über einen
   Zeitraum), Prüfzeitraum, und ob OPAA ausdrücklich im Prüfumfang liegt.
2. **Betrieb erfragen:** eingesetzte OPAA-Version und wie schnell Sicherheitsupdates eingespielt
   werden; welche Modelldienste wo laufen; zugesagte Wiederherstellungszeit und letzte Probe; wie
   die Daten bei einem Anbieterwechsel zurückkommen.
3. **Eigene Installation verlangen:** Eine OPAA-Installation führt genau eine Organisation
   ([Eine Organisation](../../handbuch/betrieb.md#eine-organisation)); mehrere Behörden trennt nur
   je eine eigene Installation.
4. **Eigenen Anteil erfüllen:** Rechte in der Anwendung vergeben, keine Inhalte über dem vereinbarten
   Schutzbedarf anbinden ([Nutzungsrichtlinie](../08-nutzung/nutzungsrichtlinie.md)), den
   Identitätsanbieter des Hauses anbinden.

## Gut zu wissen

- In der Bundesverwaltung verlangt der Mindeststandard zur Nutzung externer Cloud-Dienste Nachweise
  des Anbieters, darunter ein C5-Testat. Für Länder und Kommunen ist das Testat verbreitet, aber
  nicht zwingend.
- Die neue Fassung C5:2026 gilt für Prüfzeiträume ab dem 1.6.2027; bis dahin sind Testate nach der
  Vorgängerfassung üblich.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Kriterienkatalog C5](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Informationen-und-Empfehlungen/Empfehlungen-nach-Angriffszielen/Cloud-Computing/Kriterienkatalog-C5/kriterienkatalog-c5_node.html)
  beim BSI: der Katalog selbst mit den Kriterien für Anbieter und Kunden.
- [Mindeststandards des BSI](https://www.bsi.bund.de/DE/Themen/Oeffentliche-Verwaltung/Mindeststandards/Mindeststandards_node.html):
  welche Nachweise die Bundesverwaltung bei externen Cloud-Diensten verlangt.

**Rechtsgrundlagen und Quellen:**

| Worum es geht | Fundstelle |
|---|---|
| C5:2026 final seit April 2026, verbindlich für Prüfzeiträume ab 1.6.2027 | Fachbeiträge: [heise](https://heise.de/-11247658), [KPMG](https://kpmg.com/de/de/themen/digital-transformation/cloud-computing/mindestanforderungen-fuer-die-sicherheit-von-cloud-diensten.html) |
| C5 testiert Cloud-Anbieter, nicht Software | Fachbeitrag: [heise](https://heise.de/-11247658) |
| Nachweise des Anbieters in der Bundesverwaltung | [Mindeststandard zur Nutzung externer Cloud-Dienste](https://www.bsi.bund.de/DE/Themen/Oeffentliche-Verwaltung/Mindeststandards/Mindeststandards_node.html), am Text zu prüfen |
| Prüfkataloge für KI-Dienste (AIC4, A5); OPAA ist danach nicht geprüft | [BSI, AIC4](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Informationen-und-Empfehlungen/Kuenstliche-Intelligenz/AIC4/aic4.html); A5 laut Fachbeitrag [comquent](https://comquent.de/ressourcen/blog/bsi-a5-pruefkatalog-ki), Stand beim BSI zu prüfen |
