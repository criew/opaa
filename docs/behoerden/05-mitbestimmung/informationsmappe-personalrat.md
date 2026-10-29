---
stand: 2026-10-24
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-24
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
2. **Kurzüberblick und eckige Klammern ausfüllen.**
3. **Testinstanz bereitstellen**, siehe Abschnitt 7.
4. **Bei jedem Update** prüfen, ob sich ein Abschnitt ändert; das Delta im Update-Paket nennt die
   Änderungen.

## 1. Kurzüberblick dieser Installation

| Frage | Antwort |
|---|---|
| Eingesetzte Version, Linie, Supportende | [X.Y.Z, Linie X.Y, bis TT.MM.JJJJ] |
| Wo läuft das Sprachmodell? | [im eigenen Haus / bei Anbieter …] |
| Wo läuft das Einbettungsmodell? | [im eigenen Haus / bei Anbieter …] |
| Angebundene Quellen | [ … ] |
| Abgeschaltete Funktionen | [ … ] |
| Wer ist Systemverwaltung, Revision, Datenbankbetrieb? | [ … ] |
| Sperre lokaler Konten nach Inaktivität | [Frist] |
| Testinstanz | [Adresse, Ansprechperson] |

## 2. Was wer über mich sieht

Jede Person sieht ihre eigenen Daten. Was andere sehen, hängt an ihrer Rolle in OPAA. Die vollständige
Antwort steht in der Matrix der {{faktenblatt:rollensicht#matrix}}; die Tabelle hilft beim Durchgehen.

| Wer | Worauf achten | Nachschlagen |
|---|---|---|
| Kolleginnen und Kollegen | wer Chats, Bibliotheken und Mitgliedschaften sieht | {{faktenblatt:rollensicht#user}}, Sichtbarkeit in {{faktenblatt:datenkatalog#chats}} |
| Verwaltung einer Bibliothek, Gruppe oder eines Space | Mitglieder und Rechte am eigenen Objekt | {{faktenblatt:rollensicht#faehigkeiten}}, [Wer welche Gruppe sieht](../../handbuch/konten-und-rechte/berechtigungen.md#8-wer-welche-gruppe-sieht--und-wie-groß-sie-ist) |
| Systemverwaltung | verwaltet Konten und Rechte; sieht, welche lokalen Konten länger nicht genutzt wurden; liest fremde Inhalte nur, wenn sie sich selbst ein Recht gibt, das für alle nachlesbar gespeichert wird; kann mit „Sicht als“ befristet und begründet prüfen, was eine Person sehen würde | {{faktenblatt:rollensicht#system-admin}}, [Systemrollen und Vollmachten](../../handbuch/konten-und-rechte/berechtigungen.md#10-systemrollen-und-vollmachten) |
| Revision | liest das Protokoll mit Kennungen statt Namen und kann darin nicht nach Personen suchen | {{faktenblatt:rollensicht#auditor}}, [Pseudonymisierung im Protokoll](../../handbuch/betrieb/nachvollziehbarkeit.md#pseudonymisierung-im-protokoll) |
| Vorgesetzte | haben als Vorgesetzte keine eigene Rolle; sie sehen, was ihnen eine Rolle in OPAA (etwa Systemverwaltung) oder eine Mitgliedschaft gibt, wie alle anderen auch | {{faktenblatt:rollensicht#systemrollen}} |
| Betrieb von Datenbank, Sicherungen und Logs | technisch alles; das Produkt kann es nicht verhindern | Dienstvereinbarung, Nummer 7 |
| Ich selbst | meine Daten über die Selbstauskunft, im Umfang der Kategorien, die die eingesetzte Version ausgibt; die Übersicht der Auskunft nennt die fehlenden | {{faktenblatt:datenkatalog#self-disclosure}} |

## 3. Beispiele möglicher und ausgeschlossener Auswertungen

**Möglich, mit Zweck:**

- Die Revision prüft, wer im März Rechte an der Bibliothek „Haushalt“ geändert hat; sie fragt nach
  Objekt und Zeitraum und sieht Kennungen statt Namen.
- Nach einem Sicherheitsvorfall klärt die Revision, was ein bestimmtes Konto an einem Tag verwaltet
  hat: nur als anlassbezogene Klärung, mit Freigabe durch eine zweite Person.
- Die Systemverwaltung findet lokale Konten, mit denen sich lange niemand angemeldet hat, um sie zu
  sperren oder zu löschen; nach einer Frist sperrt OPAA sie selbst
  ({{faktenblatt:voreinstellungen#local-auth-settings-inactive-days}}).
- Der Personalrat erhält jährlich die Zahl der anlassbezogenen Klärungen nach Zweck und die Zahl
  der angehaltenen Chat-Löschungen gesperrter Konten, ohne Namen.

Was technisch möglich ist, steht je Datum im {{faktenblatt:datenkatalog}}, Spalte „Auswertungen“.
Erlaubt ist nur, was die Dienstvereinbarung vereinbart.

**Ausgeschlossen:**

- Wer stellt die meisten Fragen? Fragen werden als Chat der Person gespeichert, nicht im Protokoll;
  eine Auswertung nach Person gibt es dafür nicht.
- Rangliste der aktivsten Beschäftigten: gibt es nicht, auch nicht als Spielelement.
- Wann jemand zuletzt angemeldet war, zeigt die Kontenliste nicht als Datum.
- Wer hat eine Selbstauskunft abgerufen? Nur Nachweis und Missbrauchserkennung, keine Auswertung.
- Auswertungen direkt in der Datenbank: technisch möglich, durch die Dienstvereinbarung verboten.

## 4. Was zum Modellanbieter geht

Für jede Antwort schickt OPAA die Frage mit dem bisherigen Gesprächsverlauf, den gefundenen
Textstellen samt Titeln und dem System-Prompt an das Sprachmodell; für Chattitel und Notizen auch
Chatinhalte. Die Frage geht außerdem als Suchanfrage an das Einbettungsmodell. Wer Namen oder
Personendaten in den Chat schreibt, schickt sie mit. Vollständig steht das je Modell in
{{faktenblatt:datenfluesse#chat-model}}, {{faktenblatt:datenfluesse#embedding-model}} und den
übrigen Einträgen der {{faktenblatt:datenfluesse}}.

## 5. Wie lange bleibt was gespeichert

- Chats bleiben, bis die Person sie löscht, sofern der Space keine automatische Bereinigung hat.
- Protokoll, Rechtehistorie und anlassbezogene Klärungen werden nach Fristen gelöscht, die die
  Installation in Grenzen einstellen kann.
- Sicherungen enthalten gelöschte Daten, bis sie selbst ablaufen.

Alle Fristen mit Voreinstellung stehen in {{faktenblatt:fristen}}; was die Installation abweichend
eingestellt hat, in der Auskunft über die Datenerhebung.

## 6. Was das Abschalten bewirkt

- Manche Funktionen lassen sich für die ganze Installation abschalten, etwa die Spracheingabe. Eine
  abgeschaltete Funktion verschwindet aus Oberfläche und Schnittstelle; ihre Daten bleiben
  unverändert liegen und werden nur nicht mehr beachtet. Wird sie wieder eingeschaltet, ist der
  frühere Stand wieder da ([Funktionen abschalten](../../handbuch/betrieb/konfiguration.md#funktionen-abschalten)).
- Was schaltbar ist und wie es voreingestellt ist, steht in {{faktenblatt:voreinstellungen}}.
- Die Selbstauskunft hat statt eines Schalters eine Notabschaltung
  ({{faktenblatt:voreinstellungen#self-disclosure-settings-enabled}}); jede Änderung steht im
  Protokoll.

## 7. Testinstanz

Der Personalrat kann OPAA in einer Testinstanz der eingesetzten Version selbst ausprobieren, mit
Testkonten je Rolle und ohne echte Daten. So lässt sich etwa prüfen, was die Revision im Protokoll
sieht. Vor dem Wechsel auf eine neue Linie läuft dort deren Release-Kandidat.

## 8. Das Stufenmodell in Alltagssprache

Jedes Update kommt mit einer Liste seiner Änderungen. Für jede schlägt OPAA eine Stufe vor:

- **Bagatelle:** nur Kleinigkeiten ohne Bezug zu Personen; der Personalrat erfährt davon.
- **Anzeige:** Beschäftigte merken etwas, es wird aber nicht mehr über sie gespeichert oder gezeigt,
  etwa eine kürzere Frist. Der Personalrat kann widersprechen.
- **Zustimmung:** neue Daten, neue Einsicht, neue Auswertung, längere Frist oder neuer Weg nach
  außen. Das Update wartet auf die Zustimmung, oder die neue Funktion bleibt bis dahin aus.

Im Zweifel gilt Zustimmung. Sicherheitsupdates ohne neue Funktion werden sofort eingespielt, der
Personalrat erfährt es danach.

## 9. Glossar

| Begriff | Bedeutung |
|---|---|
| Faktenblatt | aus dem Programmcode erzeugte Übersicht einer Version, etwa über Daten, Rollen oder Fristen |
| Manifest | Beschreibung im Programmcode, aus der die Faktenblätter entstehen; Prüfungen halten sie mit dem Code gleich |
| Update-Paket | Unterlagen zu jedem Release: Faktenblätter, Delta, Betriebsfolgen |
| Delta | Liste der Änderungen gegenüber einer früheren Version, je Änderung mit Stufenvorschlag |
| Linie, LTS | Versionen einer Reihe, die Sicherheitsupdates ohne neue Funktionen bekommen sollen; LTS ist die Linie mit dem längsten Support |
| Release-Kandidat | Vorabfassung einer Version, an der die Beteiligung beginnen kann |
| Space | Arbeitsbereich mit Chats und den ihm zugeordneten Bibliotheken |
| System-Prompt | feste Anweisung von OPAA an das Sprachmodell, die bei jeder Frage mitgeht |
| Standardmodell | das Sprachmodell, das OPAA ohne andere Einstellung verwendet |
| Protokoll | Nachweis über Handlungen in OPAA, etwa vergebene Rechte, geänderte Einstellungen oder den Abruf einer Selbstauskunft; nicht über Fragen und Antworten |
| Pseudonym | Kennung statt Name im Protokoll |
| Rechtehistorie | gespeicherter Verlauf, wer wann welches Recht hatte |
| Anlassbezogene Klärung | einziger Weg, das Protokoll nach einer Person zu durchsuchen; nur mit Freigabe durch eine zweite Person |
| Sicht als | Prüfung durch die Systemverwaltung, was eine bestimmte Person sehen würde; befristet, begründet, protokolliert |
| Selbstauskunft | Download der Daten zur eigenen Person, nur durch die Person selbst |
| Notabschaltung | Schalter der Systemverwaltung, mit dem die Selbstauskunft bei einer Störung vorübergehend abgeschaltet wird |
| Auskunft über die Datenerhebung | Abruf der Einstellungen einer Installation mit der Beschreibung des Protokolls (Auszug für die Personalvertretung) |
| Mindestgruppengröße | hat eine Gruppe weniger aktive Konten, nennt die Auswahl eines Empfängers ihre Größe nicht, und „Sicht als“ sowie die Diagnose mit Space-Kontext sind für sie gesperrt; an bestehenden Freigaben und für die Systemverwaltung bleiben Zahl und Mitglieder sichtbar |

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
| Personalvertretungsgesetz des Landes | [Personalvertretungsrecht der Länder](personalvertretungsrecht-der-laender.md) |
