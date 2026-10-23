---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-23
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Produktdatenblatt

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** Behörden, die vor einer Vergabe den Markt erkunden oder eine Entscheidung vorbereiten.

Das Produktdatenblatt beschreibt OPAA auf einer Seite: was es tut, wie es betrieben wird und welche
Nachweise es mitbringt. Es dient als Grundlage für Gespräche und Fragebögen in der Markterkundung.
Jede Angabe verweist auf das Handbuch oder ein Faktenblatt der Version; maßgeblich ist immer die
Version, die die Behörde prüft.

## Was OPAA liefert

OPAA ist eine quelloffene KI-Plattform für die öffentliche Verwaltung. Sie beantwortet Fragen aus den
Dokumenten der Behörde und belegt jede Antwort mit Fundstellen. Sie läuft im Rechenzentrum der
Behörde oder eines Dienstleisters.

| Merkmal | Angabe | Nachweis |
|---|---|---|
| Kernfunktion | Fragen in Spaces stellen, Antworten mit Fundstellen, Suche ohne Antwort | [Was OPAA ist](../../handbuch/README.md#2-was-opaa-ist), [Belege](../../handbuch/spaces/suche.md#7-belege-prüfen-und-fundstellen-bilden) |
| Wissensbestände | Wissensbibliotheken aus angebundenen Quellen oder Upload | [Konnektoren](../../handbuch/arbeitsmittel/wissensbibliotheken/konnektoren/README.md#3-die-konnektoren-im-überblick), [Formate](../../handbuch/arbeitsmittel/wissensbibliotheken/formate/README.md#zugelassene-formate) |
| Rechte | je Bibliothek und Space vergeben, in jeder Suchabfrage geprüft, mit Historie | [Berechtigungen](../../handbuch/konten-und-rechte/berechtigungen.md), {{faktenblatt:rollensicht#matrix}} |
| Weitere Arbeitsmittel | Prompt-Bibliotheken; Zugriff externer KI-Werkzeuge, abschaltbar | [Prompt-Bibliotheken](../../handbuch/arbeitsmittel/prompt-bibliotheken.md), [Externe KI-Werkzeuge](../../handbuch/spaces/fremdzugaenge.md) |
| Modelle | über eine OpenAI-kompatible Schnittstelle, im eigenen Haus betreibbar | [Modelle](../../handbuch/betrieb/modelle.md), {{faktenblatt:voreinstellungen#ki-funktionen}} |
| Betrieb | Container-Images für Docker Compose oder Kubernetes mit Helm-Chart; eine oder mehrere Backend-Instanzen | [Bausteine](../../handbuch/betrieb/README.md#bausteine), [Mehrere Backend-Instanzen](../../handbuch/betrieb/mehrere-instanzen.md) |
| Anmeldung | OIDC-Identitätsanbieter oder lokale Konten | [Authentifizierung](../../handbuch/betrieb/authentifizierung.md#anmeldewege-im-betriebsmodus-oidc) |
| Daten | gespeicherte Daten, Fristen, Datenflüsse nach außen je Version beschrieben | {{faktenblatt:datenkatalog}}, {{faktenblatt:fristen}}, {{faktenblatt:datenfluesse}} |
| Nachweise je Version | Faktenblätter, Update-Paket, SBOM, Provenance | [Nachvollziehbarkeit](../../handbuch/betrieb/nachvollziehbarkeit.md), [Was ein Release liefert](../../handbuch/betrieb/releases-und-nachweise.md#was-ein-release-liefert) |
| Releases und Support | zwei Funktionsreleases im Jahr, Supportzeiträume je Linie | [Linien und Supportzeiträume](../../handbuch/betrieb/releases-und-nachweise.md#linien-und-supportzeiträume) |
| Lizenz | GNU Affero General Public License v3.0, kein Lizenzentgelt | [Lizenz](lizenz.md) |
| Barrierefreiheit | Prüfstand und bekannte Einschränkungen | [Erklärung zur Barrierefreiheit](../08-nutzung/erklaerung-barrierefreiheit.md#prüfstand) |

Nicht Teil von OPAA: ein Cloud-Dienst des Projekts, Support mit zugesagten Fristen und eine
Zertifizierung. OPAA ist darauf ausgelegt, dass ein Betreiber seine eigene Prüfung mit OPAA im
Prüfumfang bestehen kann.

## Aufgaben der Behörde

1. **Version eintragen,** auf die sich die Markterkundung bezieht, und die Faktenblätter dieser
   Version beilegen.
2. **Angaben an den eigenen Anforderungen messen,** etwa mit dem
   [Anforderungskatalog](../03-vergabe/anforderungskatalog.md) und der
   [Erfüllungsmatrix](../03-vergabe/erfuellungsmatrix.md).
3. **Offene Punkte sammeln** für Gespräche mit Dienstleistern und für die Teststellung.
4. **Markterkundung dokumentieren,** wie das Vergaberecht es verlangt.

## Gut zu wissen

- Das Datenblatt beschreibt die Software, nicht Leistungen eines Dienstleisters. Einführung, Betrieb
  und Support kauft die Behörde gegebenenfalls getrennt ein.
- Eine Markterkundung darf kein verdecktes Vergabeverfahren sein, etwa nur um Preise zu ermitteln.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Antworten auf typische Fragebögen](fragebogen-antworten.md): die Angaben nach Themen.
- [Handbuch](../../handbuch/README.md): Betrieb und Verwaltung im Einzelnen.

**Rechtsgrundlagen** für die Vergabestelle:

| Worum es geht | Fundstelle |
|---|---|
| Markterkundung oberhalb der EU-Schwellenwerte; kein Vergabeverfahren nur zur Preisermittlung | § 28 [VgV](https://www.gesetze-im-internet.de/vgv_2016/__28.html) |
| Markterkundung unterhalb der EU-Schwellenwerte | § 20 UVgO bzw. Landesrecht; geltende Fassung zu prüfen |
