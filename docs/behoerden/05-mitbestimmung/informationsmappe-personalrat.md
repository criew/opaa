---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-23
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Informationsmappe für den Personalrat

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** Behörden mit Personalrat, als Grundlage für die Beratung der
[Dienstvereinbarung](dienstvereinbarung.md).

Die Mappe erklärt dem Personalrat in Alltagssprache, was OPAA über Beschäftigte speichert, wer es
sieht und was sich damit auswerten lässt und was nicht. Die Dienststelle stellt sie zusammen und
übergibt sie mit den Faktenblättern der eingesetzten Version, bevor über die Dienstvereinbarung
verhandelt wird.

## Was OPAA liefert

- Die Faktenblätter der eingesetzten Version im Update-Paket am GitHub-Release; jede Frage unten
  verweist auf das passende Blatt.
- Die [Auskunft über die Datenerhebung](../../handbuch/betrieb/nachvollziehbarkeit.md#auskunft-über-die-datenerhebung-einer-installation)
  der eigenen Installation mit dem Auszug für die Personalvertretung.
- Das [Glossar](../../handbuch/glossar.md) im Handbuch.

## Aufgaben der Behörde

1. **Faktenblätter der eingesetzten Version beilegen** und die Version auf der Mappe vermerken.
2. **Eckige Klammern ausfüllen:** Betrieb des Sprachmodells, benannte Rollen, abgeschaltete
   Funktionen.
3. **Testinstanz bereitstellen**, siehe unten.
4. **Bei jedem Update** prüfen, ob sich ein Abschnitt ändert; das Delta im Update-Paket nennt die
   Änderungen.

## 1. Was wer über mich sieht

Jede Person sieht ihre eigenen Daten. Was andere sehen, hängt an der Rolle. Die vollständige Antwort
steht in der Matrix der {{faktenblatt:rollensicht#matrix}}; die Tabelle hilft beim Durchgehen.

| Wer | Worauf achten | Nachschlagen |
|---|---|---|
| Kolleginnen und Kollegen | wer Chats, Bibliotheken und Mitgliedschaften sieht | {{faktenblatt:rollensicht#user}}, Sichtbarkeit in {{faktenblatt:datenkatalog#chats}} |
| Verwaltung einer Bibliothek, Gruppe oder eines Space | Mitglieder und Rechte am eigenen Objekt | {{faktenblatt:rollensicht#faehigkeiten}}, [Wer welche Gruppe sieht](../../handbuch/konten-und-rechte/berechtigungen.md#8-wer-welche-gruppe-sieht--und-wie-groß-sie-ist) |
| Systemverwaltung | verwaltet Konten und Rechte; liest Inhalte nur mit einem Recht, das in der Rechtehistorie steht; „Sicht als“ nur befristet, begründet und protokolliert | {{faktenblatt:rollensicht#system-admin}}, [Systemrollen und Vollmachten](../../handbuch/konten-und-rechte/berechtigungen.md#10-systemrollen-und-vollmachten) |
| Revision | liest das Protokoll unter Pseudonym, ohne Suche nach Personen | {{faktenblatt:rollensicht#auditor}}, [Pseudonymisierung im Protokoll](../../handbuch/betrieb/nachvollziehbarkeit.md#pseudonymisierung-im-protokoll) |
| Vorgesetzte | haben keine eigene Rolle in OPAA und sehen nur, was ihnen als Nutzende zusteht | {{faktenblatt:rollensicht#systemrollen}} |
| Betrieb von Datenbank und Sicherungen | technisch alles; das Produkt kann es nicht verhindern | Dienstvereinbarung, Nummer 5 |
| Ich selbst | alles zu mir über die Selbstauskunft, sobald die Version sie enthält | {{faktenblatt:datenkatalog#self-disclosure}} |

Benannte Personen der Installation: [Systemverwaltung: …; Revision: …; Datenbankbetrieb: …;
Freigabe anlassbezogener Klärungen: …]

## 2. Beispiele möglicher und ausgeschlossener Auswertungen

**Möglich, mit Zweck:**

- Die Revision prüft, wer im März Rechte an der Bibliothek „Haushalt“ geändert hat; sie fragt nach
  Objekt und Zeitraum und sieht Pseudonyme.
- Nach einem Sicherheitsvorfall klärt die Revision, was ein bestimmtes Konto an einem Tag verwaltet
  hat: nur als anlassbezogene Klärung, mit Freigabe durch eine zweite Person und begrenzt auf
  Person, Zeitraum und Zweck.
- Eine Person lädt alles herunter, was zu ihr gespeichert ist (Selbstauskunft).
- Der Personalrat erhält jährlich die Zahl der anlassbezogenen Klärungen nach Anlass, ohne Namen.

Alle möglichen Auswertungen je Datum stehen im {{faktenblatt:datenkatalog}}, Spalte „Auswertungen“.

**Ausgeschlossen:**

- Wer stellt die meisten Fragen, wer arbeitet abends? Eine Auswertung nach Person gibt es in
  Oberfläche und Schnittstellen nicht; Fragen werden nicht protokolliert.
- Rangliste der aktivsten Beschäftigten: gibt es nicht, auch nicht als Spielelement.
- Chats einer Kollegin lesen: wer Chats sieht, steht unter Sichtbarkeit in
  {{faktenblatt:datenkatalog#chats}}; in Oberfläche und Schnittstellen gibt es keine weitere Einsicht.
- Wer hat eine Selbstauskunft abgerufen? Nur Nachweis und Missbrauchserkennung, keine Auswertung
  (Dienstvereinbarung, Nummer 7).
- Auswertungen direkt in der Datenbank: technisch möglich, durch die Dienstvereinbarung verboten.

## 3. Was zum Modellanbieter geht

Für jede Antwort schickt OPAA die Frage zusammen mit den gefundenen Textstellen aus den Dokumenten an
das Sprachmodell. Was dabei sonst mitgeht, steht in der Frage selbst: Wer Namen oder Personendaten
hineinschreibt, schickt sie mit. Welche Daten genau an welche Modellrolle gehen, steht in {{faktenblatt:datenfluesse#chat-model}} und
den übrigen Einträgen der {{faktenblatt:datenfluesse}}; wie oft je Frage, in
[Was eine Frage nach außen kostet](../../handbuch/spaces/suche.md#3-was-eine-frage-nach-außen-kostet).

In dieser Installation: [Sprachmodell läuft im eigenen Haus / bei Anbieter …, Vertrag vom …].

## 4. Was das Abschalten bewirkt

- Manche Funktionen lassen sich für die ganze Installation abschalten, etwa die Spracheingabe. Eine
  abgeschaltete Funktion verschwindet aus Oberfläche und Schnittstelle, ihre Daten bleiben aber
  liegen, bis sie nach ihrer Frist gelöscht werden; wird sie wieder eingeschaltet, ist der alte
  Stand da ([Funktionen abschalten](../../handbuch/betrieb/konfiguration.md#funktionen-abschalten)).
- Was schaltbar ist und wie es voreingestellt ist, steht in {{faktenblatt:voreinstellungen}}.
- Die Selbstauskunft hat statt eines Schalters eine Notabschaltung
  ({{faktenblatt:voreinstellungen#self-disclosure-settings-enabled}}); jede Änderung steht im
  Protokoll.

In dieser Installation abgeschaltet: [ … ]

## 5. Testinstanz

Der Personalrat kann OPAA in einer Testinstanz der eingesetzten Version selbst ausprobieren, mit
Testkonten je Rolle und ohne echte Daten: [Adresse, Zugang, Ansprechperson]. So lässt sich etwa
prüfen, was die Revision im Protokoll sieht. Vor dem Wechsel auf eine neue Linie kann dort deren
Release-Kandidat laufen.

## 6. Glossar

| Begriff | Bedeutung |
|---|---|
| Faktenblatt | aus dem Programmcode erzeugte Übersicht einer Version, etwa über Daten, Rollen oder Fristen |
| Update-Paket | Unterlagen zu jedem Release: Faktenblätter, Delta, Betriebsfolgen |
| Delta | Liste der Änderungen gegenüber der vorigen Version, je Änderung mit Stufenvorschlag |
| Stufe | Bagatelle, Anzeige oder Zustimmung nach der Dienstvereinbarung |
| Linie, LTS | Versionen einer Reihe, die Sicherheitsupdates ohne neue Funktionen bekommen; LTS ist die Linie mit dem längsten Support |
| Release-Kandidat | Vorabfassung einer Version, an der die Beteiligung beginnen kann |
| Protokoll | Nachweis über Verwaltungshandlungen, etwa vergebene Rechte, nicht über Fragen oder Antworten |
| Pseudonym | Kennung statt Name im Protokoll |
| Anlassbezogene Klärung | einziger Weg, das Protokoll nach einer Person zu durchsuchen; nur mit Freigabe durch eine zweite Person |
| Selbstauskunft | Download aller Daten zur eigenen Person, nur durch die Person selbst |
| Auskunft über die Datenerhebung | Abruf der Einstellungen einer Installation mit der Beschreibung des Protokolls (Auszug für die Personalvertretung) |
| Mindestgruppengröße | unter dieser Zahl von Personen zeigt OPAA keine Zahlen an |

## Gut zu wissen

- Die Mappe beschreibt die eingesetzte Version. Nach einem Update mit Änderungen der Stufe Anzeige
  oder Zustimmung gehört eine überarbeitete Fassung zum Update-Vermerk.
- Rückfragen zu einzelnen Einträgen der Faktenblätter beantwortet die Systemverwaltung.

## Zum Nachlesen

**Arbeitshilfen** für den Personalrat:

- [Dienstvereinbarung](dienstvereinbarung.md): Mustertext mit Stufenmodell.
- [Nachvollziehbarkeit](../../handbuch/betrieb/nachvollziehbarkeit.md) im Handbuch: wie die
  Faktenblätter entstehen und geprüft werden.
- [Update-Vermerk](../09-betrieb-update/update-vermerk.md): wie ein Update bewertet wird.

**Rechtsgrundlagen:**

| Worum es geht | Fundstelle |
|---|---|
| Unterrichtung des Personalrats mit den erforderlichen Unterlagen | § 66 Abs. 1 [BPersVG](https://www.gesetze-im-internet.de/bpersvg_2021/__66.html) |
| Mitbestimmung bei technischen Einrichtungen zur Überwachung | § 80 Abs. 1 Nr. 21 [BPersVG](https://www.gesetze-im-internet.de/bpersvg_2021/__80.html) |
| Personalvertretungsgesetz des Landes | zu prüfen |
