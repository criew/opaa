---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Notfallkonzept

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** alle Behörden, die OPAA selbst betreiben oder betreiben lassen.

Das Notfallkonzept legt fest, was bei einem Ausfall gilt und wie der Dienst wieder anläuft. Diese
Vorlage ist das Kapitel für OPAA im Notfallkonzept der Behörde. Verantwortlich ist die Behörde; die
Werte für Wiederherstellungszeit (RTO, wie lange der Dienst höchstens steht) und
Wiederherstellungspunkt (RPO, wie viel Arbeit höchstens verloren geht) legt sie fest, nicht das
Projekt.

## Was OPAA liefert

- **Was zu sichern ist** und in welcher Reihenfolge: [Sicherung und Wiederherstellung](../../handbuch/betrieb/sicherung-und-wiederherstellung.md),
  mit den Befehlen für [Docker Compose](../../handbuch/betrieb/docker-compose.md#sicherung-und-wiederherstellung)
  und [Kubernetes](../../handbuch/betrieb/kubernetes.md#sicherung-und-wiederherstellung).
- **Wie sich ein Ausfall der Modelle auswirkt** und welche Vorsorge möglich ist:
  [Ausfall eines Modells](../../handbuch/betrieb/modelle.md#ausfall-eines-modells).
- **Wie RTO und RPO belegt werden:** [Wiederherstellungsprobe](wiederherstellungsprobe.md).

| Ausfall | Wirkung | Datenverlust | Wiederanlauf |
|---|---|---|---|
| Backend | Mit einer Instanz steht der Dienst, mit mehreren antworten die übrigen | keiner | [Readiness und Liveness](../../handbuch/betrieb/images-und-laufzeit.md#readiness-und-liveness) |
| Datenbank verloren | Dienst steht | alles seit der letzten Sicherung | [Sicherung und Wiederherstellung](../../handbuch/betrieb/sicherung-und-wiederherstellung.md) |
| Originalablage verloren | Suche läuft, „Original öffnen“ und Uploads scheitern | Originale seit der letzten Sicherung | [Originalablage](../../handbuch/betrieb/originalablage.md#sicherung-und-wiederherstellung) |
| Verschlüsselungsschlüssel verloren | Zugangsdaten zu Quellen und Modellen unlesbar | nur diese Zugangsdaten | [Zugangsdaten-Verschlüsselung](../../handbuch/betrieb/netz-und-sicherheit.md#zugangsdaten-verschlüsselung) |
| Update fehlgeschlagen | Dienst steht oder läuft fehlerhaft | alles seit der Sicherung vor dem Update | [Aktualisierung und Rückweg](../../handbuch/betrieb/kubernetes.md#aktualisierung-und-rückweg) |
| Identitätsanbieter | Anmeldung nur noch mit lokalen Konten | keiner | [Notfallprozedur](../../handbuch/betrieb/authentifizierung.md#notfallprozedur-wieder-hereinkommen) |
| Modellanbieter | je nach Rolle, Anmeldung und Dokumente bleiben | keiner | [Ausfall eines Modells](../../handbuch/betrieb/modelle.md#ausfall-eines-modells) |
| Verdacht auf Kompromittierung | nach Vorfallbehandlung des Hauses | je nach Sicherung | Neuaufbau aus einer Sicherung von vor dem Vorfall, alle Geheimnisse wechseln, Meldepflichten prüfen ([NIS2](../06-informationssicherheit/nis2-bundesverwaltung.md)) |

## Aufgaben der Behörde

1. **Annahmen bestätigen**, unter denen die Tabelle gilt (siehe unten), und eigene ergänzen.
2. **RTO und RPO festlegen**, abgeleitet aus der maximal tolerierbaren Ausfallzeit der
   Business-Impact-Analyse.
3. **Sicherung einrichten lassen**, deren Abstand zum RPO passt, mit getrennt verwahrten Schlüsseln.
4. **Zuständige und Vertretungen eintragen**, auch für Datenbank, Speicher und Modelldienste.
5. **Wiederherstellungsprobe fahren lassen** und das Ergebnis unten eintragen.
6. **Fortschreiben** nach jeder Probe, nach jedem Update mit Vorbereitungsschritten und nach jeder
   Änderung am Aufbau (neuer Betriebsweg, neue Originalablage, neuer Modellanbieter).

**Annahmen:** Die Datenbank ist die einzige Wahrheit, auch für den Suchindex. Die Originale
hochgeladener Dokumente gibt es nur in der Originalablage. Wiederhergestellt wird mit derselben
OPAA-Version und demselben Embedding-Modell. Die Konfiguration liegt versioniert außerhalb der
Installation, die Images der Version sind abrufbar. Identitätsanbieter, Modelldienste und Quellen
haben eigene Notfallpläne.

## Ergebnis festhalten

```text
Installation:           [Name], OPAA-Version [Version], [eine / mehrere] Backend-Instanzen
RPO Datenbank:          [z. B. 24 Stunden]     Sicherungsintervall: [ ]
RPO Originalablage:     [gleich oder kürzer als Datenbank]
RTO bis arbeitsfähig:   [z. B. 4 Stunden]      max. tolerierbare Ausfallzeit: [ ]
Belegt durch Probe vom: [Datum]                 gemessen: [ ]
Weitere Annahmen:       [Standort und Aufbewahrung der Sicherungen, ...]

Zuständig (Name, Erreichbarkeit, Vertretung):
  Notfallverantwortung OPAA:        [ ]
  Plattform (Host oder Cluster):    [ ]
  Datenbank:                        [ ]
  Speicher (Originalablage):        [ ]
  Verwahrung der Geheimnisse:       [ ]
  Systemverwaltung in OPAA:         [ ]
  Modelldienste:                    [ ]
  Informationssicherheit:           [ ]
  Datenschutz:                      [ ]
```

„Arbeitsfähig“ heißt: Anmeldung, Spaces und belegte Antworten mit Sprung ins Original funktionieren.

## Gut zu wissen

- Ein **Ersatzmodell bei einem anderen Anbieter** ist ein neuer Datenfluss
  ({{faktenblatt:datenfluesse}}). Es darf erst aktiviert werden, wenn Datenschutzunterlagen und
  [Dienstvereinbarung](../05-mitbestimmung/dienstvereinbarung.md) es abdecken; sonst heißt die Vorsorge: Ausfall abwarten.
- Ein **RPO unter dem Sicherungsintervall** verlangt eine fortlaufende Sicherung der Datenbank und
  eine versionierte oder replizierte Originalablage. Das ist Sache des Datenbank- und
  Speicherbetriebs.
- Nach einer Rücksicherung sind alle Sitzungen beendet; die Nutzenden erfahren, dass Arbeit seit
  dem Wiederherstellungspunkt verloren ist. Von Hand gesetzte Metadaten gleicht der Betrieb danach
  ab.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [BSI-Standard 200-4 Business Continuity Management](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Standards-und-Zertifizierung/IT-Grundschutz/BSI-Standards/bsi-standards_node.html):
  wie ein Notfallmanagement aufgebaut wird, mit Business-Impact-Analyse.
- [Grundschutz-Modellierung](../06-informationssicherheit/grundschutz-modellierung.md), Bausteine
  CON.3 Datensicherungskonzept und DER.4 Notfallmanagement.
- [Sicherung und Wiederherstellung](../../handbuch/betrieb/sicherung-und-wiederherstellung.md):
  was zu sichern ist, in welcher Reihenfolge zurückgespielt wird und wie die Wiederherstellung
  geprobt wird.
- [Mehrere Backend-Instanzen](../../handbuch/betrieb/mehrere-instanzen.md): Betrieb ohne Ausfall eines
  einzelnen Backends und Update ohne Unterbrechung.
